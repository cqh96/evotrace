package io.evotrace.server.project;

import io.evotrace.common.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(projectService.list());
    }

    /** 仅返回在线(ACTIVE)项目，顶部下拉/各页面选择项目时使用。 */
    @GetMapping("/active")
    public Result<List<Map<String, Object>>> active() {
        return Result.ok(projectService.active());
    }

    public record CreateRequest(String projectKey, String name, String repoUrl) {
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public Result<Map<String, String>> create(@RequestBody CreateRequest request) {
        return Result.ok(projectService.create(request.projectKey(), request.name(), request.repoUrl()));
    }

    public record StatusRequest(String status) {
    }

    /** 项目下线/停用或重新启用：body { "status": "ACTIVE|SUSPENDED|PAUSED|OFFLINE" } */
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{projectKey}/status")
    public Result<Void> setStatus(@PathVariable String projectKey, @RequestBody StatusRequest request) {
        projectService.setStatus(projectKey, request.status());
        return Result.ok(null);
    }
}
