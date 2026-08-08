package io.evotrace.idea.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import io.evotrace.idea.api.EvotraceClient;
import io.evotrace.idea.settings.EvotraceSettings;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/**
 * Right-side tool window content: file history or project dashboard.
 * Uses inline HTML styles only — JEditorPane does not apply &lt;style&gt; blocks reliably.
 */
public class EvotracePanel extends JPanel {

    private final JLabel titleLabel = new JLabel("EvoTrace");
    private final JEditorPane content = new JEditorPane();
    private final JLabel statusLabel = new JLabel(" ");

    public EvotracePanel() {
        super(new BorderLayout());
        titleLabel.setBorder(JBUI.Borders.empty(8, 12, 4, 12));
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));

        content.setEditable(false);
        content.setContentType("text/html");
        content.setText(idleHtml());
        content.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        content.setBorder(new EmptyBorder(8, 12, 8, 12));

        statusLabel.setBorder(JBUI.Borders.empty(4, 12, 8, 12));
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        add(titleLabel, BorderLayout.NORTH);
        add(new JBScrollPane(content), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }

    public void showFileHistory(String relativePath) {
        titleLabel.setText("文件历史: " + shortName(relativePath));
        content.setText(loadingHtml());
        statusLabel.setText(EvotraceSettings.getInstance().normalizedServerUrl());

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                List<EvotraceClient.FileHistoryEntry> rows =
                        EvotraceClient.fetchFileHistory(relativePath);
                String html = renderHistory(relativePath, rows);
                ApplicationManager.getApplication().invokeLater(() -> content.setText(html));
            } catch (Exception e) {
                String server = EvotraceSettings.getInstance().normalizedServerUrl();
                ApplicationManager.getApplication().invokeLater(() ->
                        content.setText(errorHtml("无法连接 EvoTrace 服务: " + server
                                + "<br/>" + escape(e.getMessage()))));
            }
        });
    }

    public void showDashboard() {
        EvotraceSettings s = EvotraceSettings.getInstance();
        titleLabel.setText("项目面板: " + s.effectiveProjectKey());
        content.setText(loadingHtml());
        statusLabel.setText(s.normalizedServerUrl());

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                EvotraceClient.DashboardData data = EvotraceClient.fetchDashboard();
                String html = renderDashboard(data);
                ApplicationManager.getApplication().invokeLater(() -> content.setText(html));
            } catch (Exception e) {
                String server = EvotraceSettings.getInstance().normalizedServerUrl();
                ApplicationManager.getApplication().invokeLater(() ->
                        content.setText(errorHtml("无法连接 EvoTrace 服务: " + server
                                + "<br/>" + escape(e.getMessage()))));
            }
        });
    }

    private static String renderHistory(String path, List<EvotraceClient.FileHistoryEntry> rows) {
        StringBuilder sb = new StringBuilder(htmlStart());
        sb.append("<h3>").append(escape(path)).append("</h3>");
        if (rows == null || rows.isEmpty()) {
            sb.append("<p style='color:#888'>该文件暂无演化记录</p>");
        } else {
            for (EvotraceClient.FileHistoryEntry e : rows) {
                sb.append("<div style='border:1px solid #666;border-radius:6px;padding:8px;margin:0 0 8px 0'>");
                sb.append("<div><b>").append(escape(nullTo(e.changeKind(), "MODIFIED")))
                        .append("</b> <code style='background:#333;padding:1px 4px'>")
                        .append(escape(shortSha(e.commitSha())))
                        .append("</code> ")
                        .append(escape(nullTo(e.author(), "-")))
                        .append(" <span style='color:#888'>+").append(e.addLines())
                        .append("/-").append(e.delLines()).append("</span></div>");
                sb.append("<div style='color:#888'>").append(escape(nullTo(e.occurredAt(), ""))).append("</div>");
                if (e.commitMessage() != null && !e.commitMessage().isBlank()) {
                    sb.append("<div>").append(escape(e.commitMessage())).append("</div>");
                }
                if (e.summary() != null && !e.summary().isBlank()) {
                    sb.append("<div style='background:#333;padding:6px;border-radius:4px;margin-top:4px'><b>AI</b> ")
                            .append(escape(e.summary())).append("</div>");
                }
                if (e.hasDiff() && e.diff() != null && !e.diff().isBlank()) {
                    sb.append("<pre style='background:#1e1e1e;color:#ddd;padding:8px;border-radius:4px;white-space:pre-wrap'>")
                            .append(escape(e.diff())).append("</pre>");
                } else {
                    sb.append("<div style='color:#888'>本次仅记录了文件路径/行数，未入库完整 diff</div>");
                }
                sb.append("</div>");
            }
        }
        sb.append("<p style='color:#888'>EvoTrace — 系统演化追踪与智能分析平台</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String renderDashboard(EvotraceClient.DashboardData d) {
        StringBuilder sb = new StringBuilder(htmlStart());
        sb.append("<h3>").append(escape(d.projectKey)).append("</h3>");
        sb.append("<table cellpadding='8'><tr>");
        sb.append(statCell("项目", d.projectCount));
        sb.append(statCell("应用", d.appCount));
        sb.append(statCell("今日变更", d.todayChanges));
        sb.append(statCell("版本", d.releaseCount));
        sb.append("</tr></table>");
        sb.append("<h4>热点文件 (30天)</h4>");
        if (d.hotFiles.isEmpty()) {
            sb.append("<p style='color:#888'>暂无热点数据</p>");
        } else {
            for (EvotraceClient.HotFile f : d.hotFiles) {
                sb.append("<div style='padding:4px 0;border-bottom:1px dashed #666;font-family:monospace;font-size:11px'>")
                        .append(escape(f.filePath()))
                        .append(" — ").append(f.changeCount()).append(" 次</div>");
            }
        }
        sb.append("<p style='color:#888'>右键任意文件 → EvoTrace: 查看文件演化历史</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String statCell(String label, int n) {
        return "<td style='border:1px solid #666;border-radius:6px;text-align:center'>"
                + "<div style='font-size:20px;font-weight:700'>" + n + "</div>"
                + "<div>" + label + "</div></td>";
    }

    private static String htmlStart() {
        return "<html><body style='font-family:sans-serif;font-size:12px'>";
    }

    private static String loadingHtml() {
        return htmlStart() + "<p style='color:#888'>加载中…</p></body></html>";
    }

    private static String idleHtml() {
        return htmlStart()
                + "<p style='color:#888'>右键文件查看演化历史，或从 Tools / 状态栏打开项目面板。<br/>"
                + "若出现 401，请在 Settings → Tools → EvoTrace 配置账号（默认 admin / admin123）。</p>"
                + "</body></html>";
    }

    private static String errorHtml(String msg) {
        return htmlStart() + "<p style='color:#f56c6c'>" + msg + "</p></body></html>";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String nullTo(String v, String d) {
        return v == null || v.isBlank() ? d : v;
    }

    private static String shortSha(String sha) {
        if (sha == null || sha.isBlank()) return "-";
        return sha.length() > 8 ? sha.substring(0, 8) : sha;
    }

    private static String shortName(String path) {
        if (path == null) return "";
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
