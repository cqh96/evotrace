package io.evotrace.server.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 解析器插件注册表（V2.5）。
 * <p>
 * 维护内存中的插件注册表，支持从 {@code plugins/} 目录热加载 Jar 插件
 * （URLClassLoader + ServiceLoader 发现 {@link ParserPlugin} 实现）。
 * 未安装插件时返回空列表，不影响内置解析逻辑。
 */
@Component
public class ParserPluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(ParserPluginRegistry.class);

    private final Map<String, ParserPlugin> plugins = new ConcurrentHashMap<>();
    private final Map<String, URLClassLoader> loaders = new ConcurrentHashMap<>();

    /** 注册一个已实例化的插件（内置/编程安装）。 */
    public void register(ParserPlugin plugin) {
        plugins.put(plugin.id(), plugin);
        log.info("Registered parser plugin: {} v{} ({})", plugin.id(), plugin.version(), plugin.name());
    }

    /** 卸载插件。 */
    public void unregister(String pluginId) {
        plugins.remove(pluginId);
        URLClassLoader loader = loaders.remove(pluginId);
        if (loader != null) {
            try {
                loader.close();
            } catch (Exception e) {
                log.warn("close loader {}: {}", pluginId, e.getMessage());
            }
        }
    }

    /**
     * 从 Jar 文件热加载插件。
     *
     * @param jar  插件 Jar 文件
     * @param pluginId 预期插件 id（用于注册与卸载）
     */
    public void loadFromJar(File jar, String pluginId) {
        try {
            URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()},
                    getClass().getClassLoader());
            ServiceLoader<ParserPlugin> sl = ServiceLoader.load(ParserPlugin.class, loader);
            boolean found = false;
            for (ParserPlugin p : sl) {
                if (pluginId == null || pluginId.equals(p.id())) {
                    register(p);
                    loaders.put(p.id(), loader);
                    found = true;
                }
            }
            if (!found) {
                loader.close();
                log.warn("No ParserPlugin implementation in jar {}", jar.getName());
            }
        } catch (Exception e) {
            log.warn("Failed to load plugin jar {}: {}", jar.getName(), e.getMessage());
        }
    }

    /** 按 id 获取插件。 */
    public ParserPlugin get(String pluginId) {
        return plugins.get(pluginId);
    }

    /** 是否有指定类别的已注册插件(供管线在读取重输入前快速判断)。 */
    public boolean has(ParserPlugin.Category category) {
        for (ParserPlugin p : plugins.values()) {
            if (p.category() == category) return true;
        }
        return false;
    }

    /** 列出全部已注册插件。 */
    public List<ParserPlugin> all() {
        return List.copyOf(plugins.values());
    }

    /** 带溯源的插件解析结果项。 */
    public record PluginItem(String pluginId, ParserPlugin.ParseItem item) {}

    /**
     * 让指定类别的已注册插件解析输入，返回归一化清单项。
     * 单个插件抛异常只记日志，不影响其余插件与内置解析。
     */
    public List<PluginItem> parse(ParserPlugin.Category category, String input, String feature) {
        List<PluginItem> out = new java.util.ArrayList<>();
        for (ParserPlugin p : plugins.values()) {
            if (p.category() != category) continue;
            try {
                List<ParserPlugin.ParseItem> items = p.parse(input, feature);
                if (items != null) {
                    for (ParserPlugin.ParseItem item : items) {
                        out.add(new PluginItem(p.id(), item));
                    }
                }
            } catch (Exception e) {
                log.warn("plugin {} parse failed on {}: {}", p.id(), feature, e.getMessage());
            }
        }
        return out;
    }
}