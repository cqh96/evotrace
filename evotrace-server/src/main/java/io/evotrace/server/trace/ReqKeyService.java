package io.evotrace.server.trace;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * req_key 生成 / 校验 / 解析。
 * <p>
 * 键方案（见 docs/10 §8.4.2）：省略 reqKey 时采用 {@code req_key = prefix + '-' + requirement.id}，
 * 在需求 insert 后通过 {@link #backfillOnCreate} 回写；前缀取自 project_trace_setting.req_key_prefix。
 */
@Service
public class ReqKeyService {

    private final JdbcTemplate jdbc;

    public ReqKeyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读取项目 req_key_prefix（默认 REQ），作为生成键的前缀。 */
    public String generate(Long projectId) {
        try {
            String prefix = jdbc.queryForObject(
                    "SELECT req_key_prefix FROM project_trace_setting WHERE project_id = ?",
                    String.class, projectId);
            return (prefix != null && !prefix.isBlank()) ? prefix : "REQ";
        } catch (EmptyResultDataAccessException e) {
            return "REQ";
        }
    }

    /** 需求创建后回写业务键：req_key = prefix || '-' || id（仅当尚未回填）。 */
    public void backfillOnCreate(Long projectId, Long requirementId) {
        jdbc.update("""
                UPDATE requirement
                SET req_key = COALESCE((SELECT req_key_prefix FROM project_trace_setting WHERE project_id = ?), 'REQ') || '-' || id,
                    updated_at = now()
                WHERE id = ? AND req_key IS NULL
                """, projectId, requirementId);
    }

    /** 校验 req_key 在项目内是否唯一（忽略大小写）。 */
    public boolean exists(Long projectId, String reqKey) {
        if (reqKey == null || reqKey.isBlank()) {
            return false;
        }
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM requirement WHERE project_id = ? AND lower(req_key) = lower(?)",
                Integer.class, projectId, reqKey);
        return count != null && count > 0;
    }

    /** 规范化键：去除首尾空白并转大写。 */
    public String normalize(String key) {
        if (key == null) {
            return null;
        }
        return key.trim().toUpperCase();
    }
}