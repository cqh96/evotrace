package io.evotrace.server.project;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class CredentialService {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public CredentialService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    public List<Map<String, Object>> list(String projectKey) {
        return jdbc.queryForList("""
                SELECT c.id, c.api_key AS "apiKey", c.scope, c.status, c.expires_at AS "expiresAt",
                       c.created_at AS "createdAt"
                FROM api_credential c JOIN project p ON p.id = c.project_id
                WHERE p.project_key = ? AND c.status = 'ACTIVE'
                """, projectKey);
    }

    @Transactional
    public Map<String, String> rotate(String projectKey) {
        // Revoke all active credentials
        jdbc.update("""
                UPDATE api_credential SET status = 'REVOKED'
                WHERE project_id = (SELECT id FROM project WHERE project_key = ?)
                AND status = 'ACTIVE'
                """, projectKey);

        // Create new credential
        SecureRandom random = new SecureRandom();
        byte[] keyBytes = new byte[16];
        byte[] secretBytes = new byte[24];
        random.nextBytes(keyBytes);
        random.nextBytes(secretBytes);
        String apiKey = "evo_" + HexFormat.of().formatHex(keyBytes);
        String apiSecret = HexFormat.of().formatHex(secretBytes);

        jdbc.update("""
                INSERT INTO api_credential(project_id, api_key, secret_hash)
                VALUES ((SELECT id FROM project WHERE project_key = ?), ?, ?)
                """, projectKey, apiKey, passwordEncoder.encode(apiSecret));

        return Map.of("apiKey", apiKey, "apiSecret", apiSecret);
    }

    @Transactional
    public void revoke(String projectKey, Long credentialId) {
        int updated = jdbc.update("""
                UPDATE api_credential SET status = 'REVOKED'
                WHERE id = ? AND project_id = (SELECT id FROM project WHERE project_key = ?)
                """, credentialId, projectKey);
        if (updated == 0) {
            throw new IllegalArgumentException("凭证不存在或不属于该项目");
        }
    }
}
