package io.evotrace.server.project;

import io.evotrace.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    public record CreateRequest(String projectKey, String name, String repoUrl) {
    }

    @PostMapping
    public Result<Map<String, String>> create(@RequestBody CreateRequest request) {
        return Result.ok(projectService.create(request.projectKey(), request.name(), request.repoUrl()));
    }
}
