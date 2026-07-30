package io.evotrace.server.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Development seed: default admin account (admin/admin123) and a demo project
 * with timeline events so the console renders real data on first boot.
 * Remove or guard by profile for production deployments.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer users = jdbc.queryForObject("SELECT count(*) FROM sys_user", Integer.class);
        if (users != null && users == 0) {
            jdbc.update("INSERT INTO sys_user(username, password_hash, display_name, role) VALUES (?,?,?,?)",
                    "admin", passwordEncoder.encode("admin123"), "管理员", "ADMIN");
            log.info("seeded default user admin/admin123");
        }

        Integer projects = jdbc.queryForObject("SELECT count(*) FROM project WHERE project_key = 'mall'", Integer.class);
        if (projects != null && projects > 0) {
            return;
        }

        jdbc.update("INSERT INTO workspace(id, name) OVERRIDING SYSTEM VALUE VALUES (1, 'default') ON CONFLICT DO NOTHING");
        jdbc.update("""
                INSERT INTO project(id, workspace_id, project_key, name, repo_url) OVERRIDING SYSTEM VALUE
                VALUES (1, 1, 'mall', '商城系统', 'git@gitlab.internal:backend/mall.git') ON CONFLICT DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO application(id, project_id, app_key, name, tech_stack) OVERRIDING SYSTEM VALUE VALUES
                (1, 1, 'order-service', '订单服务', 'Java/SpringBoot'),
                (2, 1, 'mall-web', '商城前端', 'Vue3')
                ON CONFLICT DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO iteration(id, project_id, external_key, title, source, status) OVERRIDING SYSTEM VALUE
                VALUES (1, 1, 'REQ-2026-0712', '渠道差异化超时关单', 'JIRA', 'DONE') ON CONFLICT DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO release(id, project_id, app_id, version, base_commit, tag, env, released_at) OVERRIDING SYSTEM VALUE VALUES
                (1, 1, 1, 'v2.4.9', 'p0q1r2s', 'v2.4.9', 'prod', '2026-07-27 20:41:00+08'),
                (2, 1, 1, 'v2.5.0', 'a1b2c3d', 'v2.5.0', 'prod', '2026-07-28 18:20:00+08')
                ON CONFLICT DO NOTHING
                """);

        Object[][] events = {
                {"evt-seed-1", "seed:1", "DDL_CHANGE", "feature/timeout-config", "m0n1o2p", "lisi", "2026-07-27 19:12:00+08",
                        "order_timeout_rule 表新增 channel 字段与唯一索引 uk_rule_channel"},
                {"evt-seed-2", "seed:2", "CONFIG_CHANGE", "feature/timeout-config", null, "lisi", "2026-07-28 11:05:00+08",
                        "Nacos 新增 order.timeout.channel 配置组（3 个 key，值已脱敏）"},
                {"evt-seed-3", "seed:3", "CODE_COMMIT", "feature/timeout-config", "i7j8k9l", "zhangsan", "2026-07-28 16:30:00+08",
                        "重构 TimeoutCloseJob：抽取 ChannelTimeoutResolver 策略接口"},
                {"evt-seed-4", "seed:4", "MR_MERGED", "feature/timeout-config", "e4f5g6h", "zhangsan", "2026-07-28 17:55:00+08",
                        "超时关单由硬编码 30 分钟改为读取渠道级配置，默认行为不变"},
                {"evt-seed-5", "seed:5", "RELEASE_TAG", "main", "a1b2c3d", "zhangsan", "2026-07-28 18:20:00+08",
                        "发布 v2.5.0：订单超时关单策略配置化，涉及 3 个接口变更、1 个 DDL 变更"}
        };
        for (Object[] e : events) {
            jdbc.update("""
                    INSERT INTO change_event(project_id, app_id, event_id, idempotency_key, event_type, branch,
                                             commit_sha, author, iteration_id, summary_status, occurred_at)
                    VALUES (1, 1, ?, ?, ?, ?, ?, ?, ?, 'DONE', ?::timestamptz)
                    ON CONFLICT DO NOTHING
                    """, e[0], e[1], e[2], e[3], e[4], e[5], 1, e[6]);
            jdbc.update("""
                    INSERT INTO ai_semantic_unit(target_type, target_id, kind, content, model, confidence)
                    VALUES ('CHANGE_EVENT', ?, 'SUMMARY', ?, 'seed', 1.0) ON CONFLICT DO NOTHING
                    """, e[0], e[7]);
        }
        // Advance sequences to avoid conflicts with GENERATED ALWAYS AS IDENTITY
        jdbc.update("SELECT setval('workspace_id_seq', (SELECT COALESCE(max(id), 1) FROM workspace))");
        jdbc.update("SELECT setval('project_id_seq', (SELECT COALESCE(max(id), 1) FROM project))");
        jdbc.update("SELECT setval('application_id_seq', (SELECT COALESCE(max(id), 1) FROM application))");
        jdbc.update("SELECT setval('iteration_id_seq', (SELECT COALESCE(max(id), 1) FROM iteration))");
        jdbc.update("SELECT setval('release_id_seq', (SELECT COALESCE(max(id), 1) FROM release))");
        jdbc.update("SELECT setval('sys_user_id_seq', (SELECT COALESCE(max(id), 1) FROM sys_user))");

        log.info("seeded demo project 'mall' with {} events", events.length);
    }
}
