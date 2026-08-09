package io.evotrace.server.testplan;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 用例版本历史（对标 MeterSphere 用例版本控制）：列出某用例的历史版本、查看指定版本、回滚版本。
 */
@Service
public class CaseVersionService {

    private final JdbcTemplate jdbc;

    public CaseVersionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listVersions(Long projectId, Long caseId) {
        return jdbc.queryForList("""
                SELECT v.id, v.version, v.title, v.test_type AS "testType", v.priority,
                       v.changed_by AS "changedBy", v.created_at AS "createdAt"
                FROM test_case_version v JOIN test_case tc ON tc.id = v.test_case_id
                WHERE v.test_case_id = ? AND tc.project_id = ?
                ORDER BY v.version DESC
                """, caseId, projectId);
    }

    public Map<String, Object> versionDetail(Long projectId, Long caseId, Integer version) {
        return jdbc.queryForMap("""
                SELECT v.id, v.version, v.title, v.description, v.steps, v.test_type AS "testType",
                       v.priority, v.related_files AS "relatedFiles", v.related_apis AS "relatedApis",
                       v.tags, v.custom_fields AS "customFields", v.changed_by AS "changedBy",
                       v.created_at AS "createdAt"
                FROM test_case_version v JOIN test_case tc ON tc.id = v.test_case_id
                WHERE v.test_case_id = ? AND tc.project_id = ? AND v.version = ?
                """, caseId, projectId, version);
    }

    /** 回滚到指定版本：把该版本快照写回当前用例（并生成新版本快照记录本次变更）。 */
    @Transactional
    public void restore(Long projectId, Long caseId, Integer version) {
        // 先给当前状态打快照，形成回滚前的历史
        jdbc.queryForMap("SELECT id FROM test_case WHERE id = ? AND project_id = ?", caseId, projectId);
        // 复制当前 → 版本快照
        int next = jdbc.queryForObject(
                "SELECT COALESCE(max(version), 0) + 1 FROM test_case_version WHERE test_case_id = ?",
                Integer.class, caseId);
        jdbc.update("""
                INSERT INTO test_case_version(test_case_id, version, title, description, steps, test_type,
                    priority, tags, related_files, related_apis, custom_fields)
                SELECT ?, ?, title, description, steps, test_type, priority, tags,
                       related_files, related_apis, custom_fields
                FROM test_case WHERE id = ?
                """, caseId, next, caseId);
        // 用目标版本覆盖当前用例
        jdbc.update("""
                UPDATE test_case tc SET
                    title = v.title, description = v.description, steps = v.steps,
                    test_type = v.test_type, priority = v.priority, tags = v.tags,
                    related_files = v.related_files, related_apis = v.related_apis,
                    custom_fields = v.custom_fields, updated_at = now()
                FROM test_case_version v
                WHERE tc.id = ? AND tc.project_id = ? AND v.test_case_id = ? AND v.version = ?
                """, caseId, projectId, caseId, version);
    }
}