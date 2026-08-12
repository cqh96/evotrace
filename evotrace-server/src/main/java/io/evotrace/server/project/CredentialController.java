package io.evotrace.server.project;

import io.evotrace.common.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * API credential lifecycle management: list, rotate, revoke.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/credentials")
public class CredentialController {

    private final CredentialService credentialService;

    public CredentialController(CredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@PathVariable String projectKey) {
        return Result.ok(credentialService.list(projectKey));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/rotate")
    public Result<Map<String, String>> rotate(@PathVariable String projectKey) {
        return Result.ok(credentialService.rotate(projectKey));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{credentialId}")
    public Result<Void> revoke(@PathVariable String projectKey, @PathVariable Long credentialId) {
        credentialService.revoke(projectKey, credentialId);
        return Result.ok(null);
    }
}
