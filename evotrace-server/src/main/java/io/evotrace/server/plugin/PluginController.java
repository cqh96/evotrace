package io.evotrace.server.plugin;

import io.evotrace.common.Result;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 解析器插件市场 API（V2.5）。
 */
@RestController
@RequestMapping("/api/v1/plugins")
public class PluginController {

    private static final Logger log = LoggerFactory.getLogger(PluginController.class);

    private final JdbcTemplate jdbc;
    private final ParserPluginRegistry registry;
    private final PluginPublishService publishService;

    public PluginController(JdbcTemplate jdbc, ParserPluginRegistry registry,
                            PluginPublishService publishService) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.publishService = publishService;
    }

    /** 启动时自动加载已安装且启用的插件(内存注册表重启即空,需回填)。 */
    @PostConstruct
    public void reloadInstalledPlugins() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.plugin_id, r.jar_ref
                FROM plugin_install i
                JOIN plugin_release r ON r.plugin_id = i.plugin_id AND r.version = i.version
                WHERE i.enabled = true
                """);
        for (Map<String, Object> row : rows) {
            String jarRef = row.get("jar_ref") == null ? null : String.valueOf(row.get("jar_ref"));
            String pluginId = String.valueOf(row.get("plugin_id"));
            if (jarRef == null || jarRef.isBlank()) {
                log.warn("plugin {} has no jar_ref, skip auto-load", pluginId);
                continue;
            }
            registry.loadFromJar(new java.io.File(jarRef), pluginId);
        }
        log.info("auto-loaded {} enabled plugin(s) on startup", rows.size());
    }

    /** 市场插件列表。 */
    @GetMapping("/catalog")
    public Result<List<Map<String, Object>>> catalog() {
        return Result.ok(jdbc.queryForList("""
                SELECT plugin_id, name, category, description, author, compat_range, created_at
                FROM plugin_catalog ORDER BY created_at DESC"""));
    }

    /** 发布插件 Jar(SPI 校验 + sha256 + 落盘 + 上架)。 */
    @PostMapping(value = "/publish", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> publish(
            @org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @org.springframework.web.bind.annotation.RequestParam("version") String version,
            @org.springframework.web.bind.annotation.RequestParam(value = "minVersion", required = false) String minVersion,
            @org.springframework.web.bind.annotation.RequestParam(value = "maxVersion", required = false) String maxVersion,
            @org.springframework.web.bind.annotation.RequestParam(value = "author", required = false) String author,
            @org.springframework.web.bind.annotation.RequestParam(value = "description", required = false) String description) {
        return Result.ok(publishService.publish(file, version, minVersion, maxVersion, author, description));
    }

    /** 某插件的版本列表。 */
    @GetMapping("/{id}/releases")
    public Result<List<Map<String, Object>>> releases(@PathVariable String id) {
        return Result.ok(jdbc.queryForList("""
                SELECT plugin_id, version, min_version, max_version, sha256, created_at
                FROM plugin_release WHERE plugin_id = ? ORDER BY version DESC""", id));
    }

    /** 安装指定版本（校验和 + 热加载）。 */
    @PostMapping("/install")
    public Result<Void> install(@RequestBody Map<String, Object> body) {
        String pluginId = (String) body.get("pluginId");
        String version = (String) body.get("version");
        installRelease(pluginId, version);
        // 热加载：从插件的 jar_ref 对应路径加载
        String jarRef = (String) jdbc.queryForObject(
                "SELECT jar_ref FROM plugin_release WHERE plugin_id = ? AND version = ?",
                String.class, pluginId, version);
        if (jarRef != null) {
            registry.loadFromJar(new java.io.File(jarRef), pluginId);
        }
        return Result.ok(null);
    }

    /** 启停插件。 */
    @PutMapping("/{id}/enable")
    public Result<Void> enable(@PathVariable String id, @RequestBody Map<String, Object> body) {
        boolean enabled = body.get("enabled") == null || Boolean.parseBoolean(body.get("enabled").toString());
        jdbc.update("UPDATE plugin_install SET enabled = ? WHERE plugin_id = ?", enabled, id);
        if (!enabled) {
            registry.unregister(id);
        } else {
            // 重新加载已安装版本
            String version = jdbc.queryForObject(
                    "SELECT version FROM plugin_install WHERE plugin_id = ?", String.class, id);
            installRelease(id, version);
            String jarRef = jdbc.queryForObject(
                    "SELECT jar_ref FROM plugin_release WHERE plugin_id = ? AND version = ?",
                    String.class, id, version);
            if (jarRef != null) {
                registry.loadFromJar(new java.io.File(jarRef), id);
            }
        }
        return Result.ok(null);
    }

    /** 卸载插件。 */
    @DeleteMapping("/{id}/uninstall")
    public Result<Void> uninstall(@PathVariable String id) {
        registry.unregister(id);
        jdbc.update("DELETE FROM plugin_install WHERE plugin_id = ?", id);
        return Result.ok(null);
    }

    /** 已安装插件视图。 */
    @GetMapping("/installed")
    public Result<List<Map<String, Object>>> installed() {
        return Result.ok(jdbc.queryForList("""
                SELECT i.plugin_id, i.version, i.enabled, i.installed_at, c.name, c.category
                FROM plugin_install i
                LEFT JOIN plugin_catalog c ON c.plugin_id = i.plugin_id
                ORDER BY i.installed_at DESC"""));
    }

    private void installRelease(String pluginId, String version) {
        String sha = jdbc.queryForObject(
                "SELECT sha256 FROM plugin_release WHERE plugin_id = ? AND version = ?",
                String.class, pluginId, version);
        if (sha == null) {
            throw new IllegalArgumentException("版本不存在: " + pluginId + "@" + version);
        }
        jdbc.update("""
                INSERT INTO plugin_install (plugin_id, version, enabled)
                VALUES (?, ?, true)
                ON CONFLICT (plugin_id) DO UPDATE SET version = EXCLUDED.version, enabled = true
                """, pluginId, version);
    }
}