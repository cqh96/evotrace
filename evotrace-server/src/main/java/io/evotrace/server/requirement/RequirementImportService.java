package io.evotrace.server.requirement;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evotrace.server.ingestion.BlobStoreService;
import io.evotrace.server.testplan.TestCaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 需求文档智能解析的留档与批量落库。
 * <p>
 * 解析阶段：原文外置 blob + requirement_source_doc 留档（含 AI 原始输出）；
 * 确认阶段：编辑后的需求批量 upsert（source=AI_DOC，回溯 source_doc_id），
 * 勾选用例批量关联生成（ai_generated=true）。确认整体单事务，失败回滚。
 */
@Service
public class RequirementImportService {

    private static final Logger log = LoggerFactory.getLogger(RequirementImportService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final BlobStoreService blobStore;
    private final RequirementDocParser docParser;
    private final RequirementService requirementService;
    private final TestCaseService testCaseService;

    public RequirementImportService(JdbcTemplate jdbc, BlobStoreService blobStore,
                                    RequirementDocParser docParser,
                                    RequirementService requirementService,
                                    TestCaseService testCaseService) {
        this.jdbc = jdbc;
        this.blobStore = blobStore;
        this.docParser = docParser;
        this.requirementService = requirementService;
        this.testCaseService = testCaseService;
    }

    /** 解析并留档：返回预览（parseId + 结构化需求），失败时 parsed=false + message。 */
    public Map<String, Object> parseAndStore(Long projectId,
                                             MaterialIngestService.IngestResult ingest,
                                             String externalUrl, String actor) {
        String text = ingest.text();
        String blobRef = blobStore.put(text != null ? text : "");
        Long parseId = jdbc.queryForObject("""
                INSERT INTO requirement_source_doc(project_id, source_type, file_name,
                    external_url, blob_ref, char_count, parse_status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?) RETURNING id
                """, Long.class, projectId, ingest.sourceType(),
                "FILE".equals(ingest.sourceType()) ? ingest.sourceName() : null,
                externalUrl != null ? externalUrl
                        : ("FILE".equals(ingest.sourceType()) ? null : ingest.sourceName()),
                blobRef, text != null ? text.length() : 0, actor);

        if (!ingest.ok()) {
            markFailed(parseId);
            return Map.of("parsed", false, "parseId", parseId, "message", ingest.message());
        }
        if (!docParser.usable()) {
            markFailed(parseId);
            return Map.of("parsed", false, "parseId", parseId, "message",
                    "未配置可用 AI 模型（apiKey 缺失或为占位符），请在「AI 模型配置」启用后重试");
        }

        RequirementDocParser.ParsedDoc doc = docParser.parse(
                ingest.sourceType(), ingest.sourceName(), text);
        if (doc == null || doc.requirements() == null || doc.requirements().isEmpty()) {
            markFailed(parseId);
            return Map.of("parsed", false, "parseId", parseId, "message",
                    "AI 解析失败或未识别出需求，请补充材料细节后重试");
        }

        try {
            jdbc.update("""
                    UPDATE requirement_source_doc SET parse_status='PARSED', model=?, parse_result=?::jsonb
                    WHERE id=?
                    """, docParser.modelName(), MAPPER.writeValueAsString(doc), parseId);
        } catch (Exception e) {
            log.warn("doc-parse: persist parse_result failed: {}", e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("parsed", true);
        result.put("parseId", parseId);
        result.put("message", "");
        result.put("model", docParser.modelName());
        result.put("docTitle", doc.docTitle() != null ? doc.docTitle() : "");
        result.put("requirements", doc.requirements());
        return result;
    }

    /** 复查解析留档（校验项目归属）。 */
    public Map<String, Object> parseResult(Long projectId, Long parseId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT id, project_id, source_type AS "sourceType", file_name AS "fileName",
                       external_url AS "externalUrl", char_count AS "charCount",
                       parse_status AS "parseStatus", model,
                       parse_result::text AS "parseResult", created_by AS "createdBy",
                       created_at AS "createdAt"
                FROM requirement_source_doc WHERE id = ?
                """, parseId);
        if (!projectId.equals(row.get("project_id"))) {
            throw new IllegalArgumentException("解析记录不存在: " + parseId);
        }
        row.remove("project_id");
        return row;
    }

    /**
     * 确认导入：批量创建需求（source=AI_DOC）+ 勾选用例关联生成。单事务。
     * <p>
     * requirements 每项：{title, userStory, acceptanceCriteria, priority,
     * businessValue, description?, prototypeUrl?, cases: [{title, testType, priority, steps, selected}]}
     */
    @Transactional
    public Map<String, Object> importConfirm(Long projectId, Long parseId,
                                             List<Map<String, Object>> requirements, String actor) {
        // 校验 parseId 归属（允许 parse 失败后仍手动补录？不允许——必须基于成功解析或留档记录）
        Long pid = jdbc.queryForObject(
                "SELECT project_id FROM requirement_source_doc WHERE id = ?", Long.class, parseId);
        if (!projectId.equals(pid)) {
            throw new IllegalArgumentException("解析记录不存在: " + parseId);
        }

        List<Long> requirementIds = new ArrayList<>();
        List<Long> caseIds = new ArrayList<>();
        for (Map<String, Object> req : requirements) {
            String title = req.get("title") != null ? req.get("title").toString().trim() : "";
            if (title.isEmpty()) {
                continue;
            }
            Map<String, Object> data = new HashMap<>();
            data.put("title", title);
            data.put("description", str(req.get("description")));
            data.put("priority", strOr(req.get("priority"), "P2"));
            data.put("status", "DRAFT");
            data.put("businessValue", str(req.get("businessValue")));
            data.put("userStory", str(req.get("userStory")));
            data.put("acceptanceCriteria", str(req.get("acceptanceCriteria")));
            data.put("prototypeUrl", str(req.get("prototypeUrl")));
            data.put("productManager", actor);
            Long reqId = ((Number) requirementService.upsert(projectId, data).get("id")).longValue();
            jdbc.update("UPDATE requirement SET source='AI_DOC', source_doc_id=? WHERE id=?",
                    parseId, reqId);
            requirementIds.add(reqId);

            Object cases = req.get("cases");
            if (cases instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> c)) {
                        continue;
                    }
                    Object selected = c.get("selected");
                    if (selected != null && !Boolean.parseBoolean(selected.toString())) {
                        continue;
                    }
                    Map<String, Object> caseData = new HashMap<>();
                    caseData.put("title", strOr(c.get("title"), "未命名用例"));
                    caseData.put("test_type", strOr(c.get("testType"), "FUNCTIONAL"));
                    caseData.put("priority", strOr(c.get("priority"), "P2"));
                    caseData.put("steps", normalizeSteps(c.get("steps")));
                    caseData.put("requirement_id", reqId);
                    caseData.put("tags", "AI生成");
                    caseData.put("ai_generated", true);
                    caseIds.add(testCaseService.create(projectId, caseData));
                }
            }
        }
        log.info("doc-parse import: parseId={} requirements={} cases={} actor={}",
                parseId, requirementIds.size(), caseIds.size(), actor);
        return Map.of("success", true, "requirementIds", requirementIds, "caseIds", caseIds);
    }

    private void markFailed(Long parseId) {
        jdbc.update("UPDATE requirement_source_doc SET parse_status='FAILED' WHERE id=?", parseId);
    }

    /** steps 统一为 JSON 字符串（[{step, expected}]），与 test_case-generation 约定一致。 */
    private String normalizeSteps(Object steps) {
        if (steps == null) {
            return "[]";
        }
        if (steps instanceof String s) {
            return s;
        }
        try {
            return MAPPER.writeValueAsString(steps);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String str(Object v) {
        return v != null ? v.toString() : null;
    }

    private static String strOr(Object v, String fallback) {
        String s = str(v);
        return s != null && !s.isBlank() ? s : fallback;
    }
}
