package io.evotrace.server.project;

import io.evotrace.common.Result;
import io.evotrace.server.application.Application;
import io.evotrace.server.application.ApplicationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CRUD for applications under a project.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/applications")
public class ApplicationController {

    private final ProjectRepository projectRepository;
    private final ApplicationRepository applicationRepository;

    public ApplicationController(ProjectRepository projectRepository,
                                  ApplicationRepository applicationRepository) {
        this.projectRepository = projectRepository;
        this.applicationRepository = applicationRepository;
    }

    @GetMapping
    public Result<List<Application>> list(@PathVariable String projectKey) {
        Long projectId = projectRepository.findByProjectKey(projectKey)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectKey))
                .getId();
        return Result.ok(applicationRepository.findByProjectId(projectId));
    }

    public record AppRequest(String appKey, String name, String techStack, String owner) {}

    @PostMapping
    public Result<Application> create(@PathVariable String projectKey, @RequestBody AppRequest request) {
        Long projectId = projectRepository.findByProjectKey(projectKey)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectKey))
                .getId();
        if (applicationRepository.findByProjectIdAndAppKey(projectId, request.appKey()).isPresent()) {
            throw new IllegalArgumentException("应用标识已存在: " + request.appKey());
        }
        Application app = new Application();
        app.setProjectId(projectId);
        app.setAppKey(request.appKey());
        app.setName(request.name());
        app.setTechStack(request.techStack());
        app.setOwner(request.owner());
        return Result.ok(applicationRepository.save(app));
    }

    @PutMapping("/{appKey}")
    public Result<Application> update(@PathVariable String projectKey, @PathVariable String appKey,
                                       @RequestBody AppRequest request) {
        Long projectId = projectRepository.findByProjectKey(projectKey)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectKey))
                .getId();
        Application app = applicationRepository.findByProjectIdAndAppKey(projectId, appKey)
                .orElseThrow(() -> new IllegalArgumentException("应用不存在: " + appKey));
        if (request.name() != null) app.setName(request.name());
        if (request.techStack() != null) app.setTechStack(request.techStack());
        if (request.owner() != null) app.setOwner(request.owner());
        return Result.ok(applicationRepository.save(app));
    }
}
