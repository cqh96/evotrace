package io.evotrace.server.qa;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Evolution Q&A. M2 interim: keyword retrieval over AI summaries + reference list.
 * TODO(M3): RAG with pgvector + ChatClient streaming (SSE).
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/qa")
public class QaController {

    private final JdbcTemplate jdbcTemplate;

    public QaController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record AskRequest(String question) {
    }

    @PostMapping
    public Result<Map<String, Object>> ask(@PathVariable String projectKey, @RequestBody AskRequest request) {
        String keyword = "%" + request.question().replaceAll("\\s+", "%") + "%";
        List<Map<String, Object>> hits = jdbcTemplate.queryForList("""
                SELECT c.event_id AS id, c.event_type AS type, c.commit_sha AS sha, s.content AS title
                FROM change_event c
                JOIN project p ON p.id = c.project_id AND p.project_key = ?
                JOIN ai_semantic_unit s ON s.target_type = 'CHANGE_EVENT' AND s.target_id = c.event_id
                WHERE s.content ILIKE ? ORDER BY c.occurred_at DESC LIMIT 5
                """, projectKey, keyword);

        String answer = hits.isEmpty()
                ? "未在演化记录中检索到与问题相关的变更。RAG 语义问答将在 M3 提供（pgvector + LLM）。"
                : "基于关键字检索到 " + hits.size() + " 条相关变更，见下方引用。语义化推理问答将在 M3 接入大模型后提供。";
        return Result.ok(Map.of("answer", answer, "references", hits));
    }
}
