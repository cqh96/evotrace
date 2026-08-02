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
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ApiCredentialRepository credentialRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public ProjectService(ProjectRepository projectRepository, ApiCredentialRepository credentialRepository,
                          JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.projectRepository = projectRepository;
        this.credentialRepository = credentialRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    public List<Map<String, Object>> list() {
        return jdbcTemplate.queryForList("""
                SELECT p.id, p.project_key AS "projectKey", p.name, p.repo_url AS "repoUrl",
                       p.status,
                       (SELECT string_agg(DISTINCT a.tech_stack, ',') FROM application a WHERE a.project_id = p.id) AS "techStack",
                       (SELECT max(c.occurred_at) FROM change_event c WHERE c.project_id = p.id) AS "lastEventAt"
                FROM project p WHERE p.status = 'ACTIVE' ORDER BY p.id
                """);
    }

    @Transactional
    public Map<String, String> create(String projectKey, String name, String repoUrl) {
        if (projectRepository.findByProjectKey(projectKey).isPresent()) {
            throw new IllegalArgumentException("项目标识已存在: " + projectKey);
        }
        List<Long> workspaceIds = jdbcTemplate.queryForList(
                "SELECT id FROM workspace ORDER BY id LIMIT 1", Long.class);
        Long workspaceId;
        if (workspaceIds.isEmpty()) {
            workspaceId = jdbcTemplate.queryForObject(
                    "INSERT INTO workspace(name) VALUES ('default') RETURNING id", Long.class);
        } else {
            workspaceId = workspaceIds.get(0);
        }
        Project project = new Project();
        project.setWorkspaceId(workspaceId);
        project.setProjectKey(projectKey);
        project.setName(name);
        project.setRepoUrl(repoUrl);
        projectRepository.save(project);

        SecureRandom random = new SecureRandom();
        byte[] keyBytes = new byte[16];
        byte[] secretBytes = new byte[24];
        random.nextBytes(keyBytes);
        random.nextBytes(secretBytes);
        String apiKey = "evo_" + HexFormat.of().formatHex(keyBytes);
        String apiSecret = HexFormat.of().formatHex(secretBytes);

        ApiCredential credential = new ApiCredential();
        credential.setProjectId(project.getId());
        credential.setApiKey(apiKey);
        credential.setSecretHash(passwordEncoder.encode(apiSecret));
        credential.setHmacKey(apiSecret);
        credentialRepository.save(credential);

        return Map.of("apiKey", apiKey, "apiSecret", apiSecret);
    }
}
