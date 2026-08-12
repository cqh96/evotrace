package io.evotrace.server.governance;

import io.evotrace.common.Result;
import io.evotrace.server.iam.SysUserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 项目成员与角色权限管理（P1，对标 TAPD 权限体系简化版）。
 * 角色：ADMIN / PM / DEVELOPER / QA / OPS。用于前端菜单与数据权限过滤。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/members")
public class ProjectMemberController {

    private static final List<String> ROLES = List.of("ADMIN", "PM", "DEVELOPER", "QA", "OPS");

    private final JdbcTemplate jdbc;
    private final SysUserRepository userRepository;

    public ProjectMemberController(JdbcTemplate jdbc, SysUserRepository userRepository) {
        this.jdbc = jdbc;
        this.userRepository = userRepository;
    }

    private Long projectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@PathVariable String projectKey) {
        return Result.ok(jdbc.queryForList("""
                SELECT pm.id, pm.user_id AS "userId", pm.role, pm.created_at AS "createdAt",
                       u.username, u.display_name AS "displayName"
                FROM project_member pm JOIN sys_user u ON u.id = pm.user_id
                WHERE pm.project_id = ? ORDER BY pm.role, pm.id
                """, projectId(projectKey)));
    }

    /** 添加成员：username + role。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    @PostMapping
    public Result<Map<String, Object>> addMember(@PathVariable String projectKey,
                                                 @RequestBody Map<String, Object> body) {
        String username = body.get("username") != null ? body.get("username").toString() : null;
        String role = body.get("role") != null ? body.get("role").toString().toUpperCase() : "DEVELOPER";
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("缺少用户名");
        }
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException("非法角色: " + role);
        }
        Long userId = userRepository.findByUsername(username)
                .map(u -> u.getId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + username));
        jdbc.update("""
                INSERT INTO project_member(project_id, user_id, role) VALUES (?, ?, ?)
                ON CONFLICT (project_id, user_id) DO UPDATE SET role = EXCLUDED.role
                """, projectId(projectKey), userId, role);
        return Result.ok(Map.of("success", true, "userId", userId, "role", role));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    @DeleteMapping("/{memberId}")
    public Result<Void> removeMember(@PathVariable String projectKey, @PathVariable Long memberId) {
        jdbc.update("DELETE FROM project_member WHERE id = ? AND project_id = ?",
                memberId, projectId(projectKey));
        return Result.ok(null);
    }

    /** 当前用户在该项目的角色（未配置时默认 ADMIN，便于演示）。 */
    @GetMapping("/me")
    public Result<Map<String, Object>> myRole(@PathVariable String projectKey,
                                              @RequestParam(defaultValue = "admin") String username) {
        String role;
        try {
            role = jdbc.queryForObject("""
                    SELECT pm.role FROM project_member pm
                    JOIN sys_user u ON u.id = pm.user_id
                    WHERE pm.project_id = ? AND u.username = ?
                    """, String.class, projectId(projectKey), username);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            role = "ADMIN";
        }
        return Result.ok(Map.of("role", role, "roles", ROLES));
    }
}