package io.evotrace.server.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * artifact_link 边表 CRUD / 确认 / 驳回 / 物理删除 / 审计。
 * <p>
 * 唯一约束 uk_artifact_link_active 保证 status=ACTIVE 时 (project, from, to, link_type) 唯一，
 * 因此同键的 ACTIVE 边幂等返回，PENDING 边可重复存在。
 */
@Service
public class ArtifactLinkService {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public ArtifactLinkService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 手动 / 外部建边：source=MANUAL、confidence=100、status=ACTIVE；写 audit(CREATE)。
     * 已存在 ACTIVE 同键则幂等返回已有边。
     */
    public Map<String, Object> createLink(Long projectId, String fromType, String fromId,
                                          String toType, String toId, String linkType,
                                          Integer confidence, String source,
                                          Map<String, Object> meta, String actor) {
        String src = source != null ? source : "MANUAL";
        int conf = confidence != null ? confidence : 100;
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT * FROM artifact_link
                WHERE project_id = ? AND from_type = ? AND from_id = ? AND to_type = ? AND to_id = ?
                  AND link_type = ? AND status = 'ACTIVE'
                """, projectId, fromType, fromId, toType, toId, linkType);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO artifact_link(project_id, from_type, from_id, to_type, to_id,
                    link_type, confidence, source, status, created_by, meta)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?::jsonb)
                RETURNING id
                """, Long.class, projectId, fromType, fromId, toType, toId, linkType,
                conf, src, actor, toJson(meta));
        audit(projectId, id, "CREATE", actor, Map.of(
                "fromType", fromType, "fromId", fromId,
                "toType", toType, "toId", toId, "source", src));
        return jdbc.queryForMap("SELECT * FROM artifact_link WHERE id = ?", id);
    }

    /**
     * 自动建边：已存在 ACTIVE 同键则更新 confidence/source/meta；否则插入。
     * status 按 confidence 决定：≥85 ACTIVE，60-84 PENDING，&lt;60 不写。
     */
    public void upsertAuto(Long projectId, String fromType, String fromId,
                           String toType, String toId, String linkType,
                           Integer confidence, String source, Map<String, Object> meta) {
        int conf = confidence != null ? confidence : 85;
        if (conf < 60) {
            return; // 置信度过低，不建边（悬空键由治理查询即时检测）
        }
        String status = conf >= 85 ? "ACTIVE" : "PENDING";
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT * FROM artifact_link
                WHERE project_id = ? AND from_type = ? AND from_id = ? AND to_type = ? AND to_id = ?
                  AND link_type = ? AND status = 'ACTIVE'
                """, projectId, fromType, fromId, toType, toId, linkType);
        if (!existing.isEmpty()) {
            jdbc.update("""
                    UPDATE artifact_link SET confidence = ?, source = ?, meta = ?::jsonb, updated_at = now()
                    WHERE id = ?
                    """, conf, source, toJson(meta), ((Number) existing.get(0).get("id")).longValue());
            return;
        }
        jdbc.update("""
                INSERT INTO artifact_link(project_id, from_type, from_id, to_type, to_id,
                    link_type, confidence, source, status, meta)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, projectId, fromType, fromId, toType, toId, linkType,
                conf, source, status, toJson(meta));
    }

    /** PENDING → ACTIVE，写 audit(CONFIRM)。返回受影响行数。 */
    public int confirm(Long projectId, Long linkId, String actor) {
        int n = jdbc.update("""
                UPDATE artifact_link SET status = 'ACTIVE', updated_at = now()
                WHERE id = ? AND project_id = ? AND status = 'PENDING'
                """, linkId, projectId);
        if (n > 0) {
            audit(projectId, linkId, "CONFIRM", actor, Map.of());
        }
        return n;
    }

    /** PENDING → REJECTED，写 audit(REJECT)。返回受影响行数。 */
    public int reject(Long projectId, Long linkId, String reason, String actor) {
        int n = jdbc.update("""
                UPDATE artifact_link SET status = 'REJECTED', updated_at = now()
                WHERE id = ? AND project_id = ? AND status = 'PENDING'
                """, linkId, projectId);
        if (n > 0) {
            audit(projectId, linkId, "REJECT", actor,
                    reason != null ? Map.of("reason", reason) : Map.of());
        }
        return n;
    }

    /** 物理删除边 + audit(DELETE)（规格选定：物理删 ACTIVE 边简化唯一索引）。返回受影响行数。 */
    public int delete(Long projectId, Long linkId, String actor) {
        int n = jdbc.update("DELETE FROM artifact_link WHERE id = ? AND project_id = ?", linkId, projectId);
        if (n > 0) {
            audit(projectId, linkId, "DELETE", actor, Map.of());
        }
        return n;
    }

    public List<Map<String, Object>> listByFrom(Long projectId, String fromType, String fromId) {
        return jdbc.queryForList("""
                SELECT * FROM artifact_link WHERE project_id = ? AND from_type = ? AND from_id = ?
                ORDER BY id
                """, projectId, fromType, fromId);
    }

    public List<Map<String, Object>> listByTo(Long projectId, String toType, String toId) {
        return jdbc.queryForList("""
                SELECT * FROM artifact_link WHERE project_id = ? AND to_type = ? AND to_id = ?
                ORDER BY id
                """, projectId, toType, toId);
    }

    /** 写 trace_link_audit 审计行。 */
    public void audit(Long projectId, Long linkId, String action, String actor, Map<String, Object> detail) {
        jdbc.update("""
                INSERT INTO trace_link_audit(project_id, link_id, action, actor, detail)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, projectId, linkId, action, actor, toJson(detail));
    }

    private static String toJson(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(meta);
        } catch (Exception e) {
            return "{}";
        }
    }
}