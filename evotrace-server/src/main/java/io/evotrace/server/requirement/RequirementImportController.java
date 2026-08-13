package io.evotrace.server.requirement;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 需求文档智能解析入口：链接 / 文档上传 / 原型+轻PRD → AI 解析预览 → 确认批量落库。
 * 鉴权与取数惯例同 {@link RequirementWorkbenchController}。
 */
@RestController
@RequestMapping("/api/v1/pm")
public class RequirementImportController {

    private final JdbcTemplate jdbc;
    private final MaterialIngestService ingestService;
    private final RequirementImportService importService;

    public RequirementImportController(JdbcTemplate jdbc, MaterialIngestService ingestService,
                                       RequirementImportService importService) {
        this.jdbc = jdbc;
        this.ingestService = ingestService;
        this.importService = importService;
    }

    private Long projectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    private static String actor() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        return auth != null && auth.getName() != null && !auth.getName().isBlank()
                ? auth.getName() : "PM";
    }

    /** 链接解析（body: {url, prdText?}；prdText 非空即"原型+轻PRD"组合模式）。 */
    @PostMapping("/requirements/parse-link")
    public Result<Map<String, Object>> parseLink(@RequestParam String projectKey,
                                                 @RequestBody Map<String, Object> body) {
        String url = body.get("url") != null ? body.get("url").toString() : null;
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("缺少 url");
        }
        String prdText = body.get("prdText") != null ? body.get("prdText").toString() : null;
        MaterialIngestService.IngestResult ingest = prdText != null && !prdText.isBlank()
                ? ingestService.fromPrototypeWithPrd(url, prdText)
                : ingestService.fromLink(url);
        return Result.ok(importService.parseAndStore(projectId(projectKey), ingest, url, actor()));
    }

    /** 文档上传解析（multipart: file，≤20MB，pdf/docx/md/txt/html/xlsx）。 */
    @PostMapping("/requirements/parse-doc")
    public Result<Map<String, Object>> parseDoc(@RequestParam String projectKey,
                                                @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        MaterialIngestService.IngestResult ingest = ingestService.fromFile(file);
        return Result.ok(importService.parseAndStore(projectId(projectKey), ingest, null, actor()));
    }

    /** 复查解析留档。 */
    @GetMapping("/requirements/parse-result/{parseId}")
    public Result<Map<String, Object>> parseResult(@RequestParam String projectKey,
                                                   @PathVariable Long parseId) {
        return Result.ok(importService.parseResult(projectId(projectKey), parseId));
    }

    /** 确认导入（body: {parseId, requirements: [...]}），批量落库需求+用例。 */
    @PostMapping("/requirements/import-confirm")
    public Result<Map<String, Object>> importConfirm(@RequestParam String projectKey,
                                                     @RequestBody Map<String, Object> body) {
        Object parseIdObj = body.get("parseId");
        if (parseIdObj == null) {
            throw new IllegalArgumentException("缺少 parseId");
        }
        Object reqs = body.get("requirements");
        if (!(reqs instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("requirements 不能为空");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> requirements = (List<Map<String, Object>>) (List<?>) list;
        return Result.ok(importService.importConfirm(projectId(projectKey),
                ((Number) parseIdObj).longValue(), requirements, actor()));
    }
}
