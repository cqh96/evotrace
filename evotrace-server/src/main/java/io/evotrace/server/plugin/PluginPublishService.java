package io.evotrace.server.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.regex.Pattern;

/**
 * 插件发布：接收第三方打包的 ParserPlugin Jar，校验 SPI 声明后上架市场。
 * <p>插件身份（pluginId/name/category）以 Jar 内 SPI 实现为准，
 * 发布者只提供版本与描述信息，避免目录与实现不一致。</p>
 */
@Service
public class PluginPublishService {

    private static final Logger log = LoggerFactory.getLogger(PluginPublishService.class);
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\d+(\\.\\d+){1,3}([-+].*)?$");

    private final JdbcTemplate jdbc;
    private final Path pluginRoot;

    public PluginPublishService(JdbcTemplate jdbc,
                                @Value("${evotrace.blob.dir:./data/blobs}") String blobDir) {
        this.jdbc = jdbc;
        this.pluginRoot = Path.of(blobDir).toAbsolutePath().normalize().resolve("plugins");
    }

    /**
     * 发布插件 Jar：SPI 校验 → sha256 → 落盘 → 上架 catalog/release。
     */
    public Map<String, Object> publish(MultipartFile file, String version,
                                       String minVersion, String maxVersion,
                                       String author, String description) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请上传插件 Jar 文件");
        }
        if (version == null || !VERSION_PATTERN.matcher(version.trim()).matches()) {
            throw new IllegalArgumentException("版本号格式不合法,应为 x.y.z(如 1.0.0)");
        }
        version = version.trim();

        File tmp = null;
        try {
            tmp = File.createTempFile("plugin-publish-", ".jar");
            file.transferTo(tmp);

            // 以 Jar 内 SPI 实现为准提取插件身份
            ParserPlugin impl = inspect(tmp);
            String pluginId = impl.id();
            if (pluginId == null || pluginId.isBlank()) {
                throw new IllegalArgumentException("插件 id() 不能为空");
            }

            String sha256 = sha256(tmp);
            Path jarDir = pluginRoot.resolve(pluginId);
            Files.createDirectories(jarDir);
            Path target = jarDir.resolve(version + ".jar");
            Files.copy(tmp.toPath(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            String compatRange = compatRange(minVersion, maxVersion);
            // catalog:upsert(以 jar 内声明为准)
            jdbc.update("""
                    INSERT INTO plugin_catalog(plugin_id, name, category, description, author, compat_range)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (plugin_id) DO UPDATE SET
                        name = EXCLUDED.name, category = EXCLUDED.category,
                        description = COALESCE(EXCLUDED.description, plugin_catalog.description),
                        author = COALESCE(EXCLUDED.author, plugin_catalog.author),
                        compat_range = COALESCE(EXCLUDED.compat_range, plugin_catalog.compat_range)
                    """, pluginId, impl.name(), impl.category().name(),
                    description, author, compatRange);
            // release:同版本覆盖(允许修正 jar)
            jdbc.update("""
                    INSERT INTO plugin_release(plugin_id, version, min_version, max_version, sha256, jar_ref)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (plugin_id, version) DO UPDATE SET
                        min_version = EXCLUDED.min_version, max_version = EXCLUDED.max_version,
                        sha256 = EXCLUDED.sha256, jar_ref = EXCLUDED.jar_ref
                    """, pluginId, version, minVersion, maxVersion, sha256, target.toString());

            log.info("plugin published: {}@{} ({}), jar={}", pluginId, version, impl.category(), target);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pluginId", pluginId);
            out.put("name", impl.name());
            out.put("category", impl.category().name());
            out.put("version", version);
            out.put("sha256", sha256);
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("publish plugin failed: {}", e.getMessage());
            throw new IllegalArgumentException("插件发布失败: " + e.getMessage());
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    /** 校验 jar 内含且仅含一个 ParserPlugin SPI 实现,返回该实现(用于读取元数据)。 */
    private ParserPlugin inspect(File jar) {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()},
                getClass().getClassLoader())) {
            ServiceLoader<ParserPlugin> sl = ServiceLoader.load(ParserPlugin.class, loader);
            List<ParserPlugin> found = new ArrayList<>();
            for (ParserPlugin p : sl) found.add(p);
            if (found.isEmpty()) {
                throw new IllegalArgumentException(
                        "jar 内未发现 ParserPlugin SPI 实现,请检查 META-INF/services/io.evotrace.server.plugin.ParserPlugin");
            }
            if (found.size() > 1) {
                throw new IllegalArgumentException("一个 jar 只允许一个插件实现,发现 " + found.size() + " 个");
            }
            return found.get(0);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("插件 jar 无法加载: " + e.getMessage());
        }
    }

    private String sha256(File f) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath()));
        return HexFormat.of().formatHex(digest);
    }

    private String compatRange(String minVersion, String maxVersion) {
        if (minVersion == null && maxVersion == null) return null;
        return (minVersion == null ? "?" : minVersion) + " – " + (maxVersion == null ? "?" : maxVersion);
    }
}
