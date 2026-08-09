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
 * Development seed: default admin account (admin/admin123).
 * 不做任何演示项目/数据写入，避免硬编码默认项目（如 mall）。
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
    }
}
