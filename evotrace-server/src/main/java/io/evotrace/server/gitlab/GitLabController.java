package io.evotrace.server.gitlab;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * GitLab 仓库集成 API（V2.5）。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/gitlab")
public class GitLabController {

    private final JdbcTemplate jdbc;
    private final GitLabService gitLabService;

    public GitLabController(JdbcTemplate jdbc, GitLabService gitLabService) {
        this.jdbc = jdbc;
        this.gitLabService = gitLabService;
    }

    private Long projectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    /** 配置 GitLab 连接。 */
    @PostMapping("/connect")
    public Result<Void> connect(@PathVariable String projectKey, @RequestBody Map<String, Object> body) {
        gitLabService.connect(
                projectId(projectKey),
                (String) body.get("baseUrl"),
                body.get("authType") == null ? "PAT" : body.get("authType").toString(),
                (String) body.get("token"),
                (String) body.get("namespace"));
        return Result.ok(null);
    }

    /** 导入仓库（clone + 历史回填）。 */
    @PostMapping("/repos/import")
    public Result<Map<String, Object>> importRepo(@PathVariable String projectKey,
                                                  @RequestBody Map<String, Object> body) {
        return Result.ok(gitLabService.importRepo(
                projectId(projectKey),
                (String) body.get("repoPath"),
                body.get("defaultBranch") == null ? "main" : body.get("defaultBranch").toString()));
    }

    /** 手动增量同步。 */
    @PostMapping("/repos/{id}/sync")
    public Result<Map<String, Object>> sync(@PathVariable String projectKey, @PathVariable Long id) {
        return Result.ok(gitLabService.sync(projectId(projectKey), id));
    }

    /** 仓库列表与同步状态。 */
    @GetMapping("/repos")
    public Result<List<Map<String, Object>>> repos(@PathVariable String projectKey) {
        return Result.ok(gitLabService.listRepos(projectId(projectKey)));
    }

    /** 同步日志。 */
    @GetMapping("/repos/{id}/logs")
    public Result<List<Map<String, Object>>> logs(@PathVariable String projectKey, @PathVariable Long id) {
        return Result.ok(gitLabService.logs(id));
    }
}