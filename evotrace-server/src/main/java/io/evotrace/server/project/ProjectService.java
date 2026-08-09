package io.evotrace.server.project;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** 合法项目状态：ACTIVE 在线 / SUSPENDED、PAUSED 停用 / OFFLINE 下线。 */
    private static final Set<String> VALID_STATUS = Set.of("ACTIVE", "SUSPENDED", "PAUSED", "OFFLINE");

    public List<Map<String, Object>> list() {
        // 返回全部项目（含停用/下线），ACTIVE 排前，便于接入管理页重新启用。
        return jdbcTemplate.queryForList("""
                SELECT p.id, p.project_key AS "projectKey", p.name, p.repo_url AS "repoUrl",
                       p.status,
                       (SELECT string_agg(DISTINCT a.tech_stack, ',') FROM application a WHERE a.project_id = p.id) AS "techStack",
                       (SELECT max(c.occurred_at) FROM change_event c WHERE c.project_id = p.id) AS "lastEventAt"
                FROM project p ORDER BY (p.status = 'ACTIVE') DESC, p.id
                """);
    }

    /** 仅返回在线(ACTIVE)项目，供顶部下拉/各页面选择使用，停用/下线项目不出现。 */
    public List<Map<String, Object>> active() {
        return jdbcTemplate.queryForList("""
                SELECT p.id, p.project_key AS "projectKey", p.name, p.repo_url AS "repoUrl",
                       p.status,
                       (SELECT string_agg(DISTINCT a.tech_stack, ',') FROM application a WHERE a.project_id = p.id) AS "techStack",
                       (SELECT max(c.occurred_at) FROM change_event c WHERE c.project_id = p.id) AS "lastEventAt"
                FROM project p WHERE p.status = 'ACTIVE' ORDER BY p.id
                """);
    }

    /** 将项目设为下线/停用，或重新启用。停用后新事件将被拒绝（见 IngestionService）。 */
    @Transactional
    public void setStatus(String projectKey, String status) {
        if (!VALID_STATUS.contains(status)) {
            throw new IllegalArgumentException("非法项目状态: " + status);
        }
        int n = jdbcTemplate.update("UPDATE project SET status = ? WHERE project_key = ?", status, projectKey);
        if (n == 0) {
            throw new IllegalArgumentException("项目不存在: " + projectKey);
        }
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
