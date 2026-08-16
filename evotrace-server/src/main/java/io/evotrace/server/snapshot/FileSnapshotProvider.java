package io.evotrace.server.snapshot;

import io.evotrace.server.ingestion.BlobStoreService;
import io.evotrace.server.plugin.ParserPlugin;
import io.evotrace.server.plugin.ParserPluginRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds snapshot items from {@code change_file} rows in the release window:
 * the latest state of each file touched between the previous and current
 * release. Category is inferred from the file path.
 */
@Component
public class FileSnapshotProvider implements SnapshotItemProvider {

    private final JdbcTemplate jdbc;
    private final BlobStoreService blobStore;
    private final ParserPluginRegistry pluginRegistry;

    public FileSnapshotProvider(JdbcTemplate jdbc, BlobStoreService blobStore,
                                ParserPluginRegistry pluginRegistry) {
        this.jdbc = jdbc;
        this.blobStore = blobStore;
        this.pluginRegistry = pluginRegistry;
    }

    @Override
    public String name() {
        return "file";
    }

    @Override
    public List<SnapshotDraft> collect(Long projectId, Long appId,
                                       OffsetDateTime fromInclusive, OffsetDateTime toInclusive,
                                       String version) {
        // DISTINCT ON: latest change_file state per path within the window
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT DISTINCT ON (cf.file_path)
                       cf.file_path AS "path", cf.change_kind AS "kind",
                       cf.add_lines AS "addLines", cf.del_lines AS "delLines",
                       cf.diff_blob_ref AS "diffBlobRef"
                FROM change_file cf
                JOIN change_event ce ON ce.event_id = cf.event_id
                WHERE ce.project_id = ? AND ce.app_id = ? AND ce.occurred_at > ? AND ce.occurred_at <= ?
                ORDER BY cf.file_path, ce.occurred_at DESC
                """, projectId, appId, fromInclusive, toInclusive);

        List<SnapshotDraft> drafts = new ArrayList<>();
        // CODE 类插件:每个变更文件喂 diff 内容(无 diff 时喂元数据摘要),插件产物带溯源并入快照
        boolean codePlugins = pluginRegistry.has(ParserPlugin.Category.CODE);
        for (var row : rows) {
            String path = (String) row.get("path");
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("path", path);
            content.put("changeKind", row.get("kind"));
            content.put("addLines", row.get("addLines"));
            content.put("delLines", row.get("delLines"));
            drafts.add(new SnapshotDraft(classify(path), path, content));

            if (codePlugins) {
                String diff = blobStore.get(row.get("diffBlobRef") == null ? null
                        : String.valueOf(row.get("diffBlobRef")));
                String input = diff != null ? diff
                        : "path=" + path + "\nkind=" + row.get("kind")
                          + "\naddLines=" + row.get("addLines") + "\ndelLines=" + row.get("delLines");
                try {
                    for (ParserPluginRegistry.PluginItem pi
                            : pluginRegistry.parse(ParserPlugin.Category.CODE, input, path)) {
                        Map<String, Object> pc = new LinkedHashMap<>();
                        pc.put("detail", pi.item().detail());
                        pc.put("source", "plugin");
                        pc.put("pluginId", pi.pluginId());
                        drafts.add(new SnapshotDraft(pi.item().type(), pi.item().name(), pc));
                    }
                } catch (Exception e) {
                    // 插件异常已在 registry.parse 内隔离,这里兜底防管线中断
                }
            }
        }
        return drafts;
    }

    /** Infer snapshot category from the file path. */
    static String classify(String path) {
        if (path == null) return "STRUCTURE";
        String p = path.toLowerCase();
        if (p.endsWith(".sql")) return "SCHEMA";
        if (p.endsWith(".yml") || p.endsWith(".yaml") || p.endsWith(".properties")
                || p.endsWith(".json") || p.endsWith(".toml") || p.endsWith(".xml")) {
            return "CONFIG";
        }
        if (p.endsWith("controller.java") || p.contains("/api/")
                || p.contains("/controller/") || p.contains("/controllers/")) {
            return "API";
        }
        return "STRUCTURE";
    }
}
