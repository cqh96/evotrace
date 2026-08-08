const vscode = require('vscode');
const https = require('https');
const http = require('http');

/**
 * EvoTrace VS Code Extension
 * Right-click file → "查看文件演化历史" → side panel with AI summaries
 */
function activate(context) {
    console.log('EvoTrace extension activated');

    // Command: Show file history (context menu)
    const fileHistoryCmd = vscode.commands.registerCommand('evotrace.showFileHistory', async (uri) => {
        const filePath = uri ? uri.fsPath : vscode.window.activeTextEditor?.document.uri.fsPath;
        if (!filePath) {
            vscode.window.showWarningMessage('请先打开一个文件');
            return;
        }

        const config = vscode.workspace.getConfiguration('evotrace');
        const serverUrl = config.get('serverUrl', 'http://43.155.130.69');
        const projectKey = config.get('defaultProject', 'maidao_merchant');

        // Extract relative path (strip workspace folder prefix)
        const workspaceFolder = vscode.workspace.getWorkspaceFolder(uri || vscode.window.activeTextEditor.document.uri);
        let relPath = filePath;
        if (workspaceFolder) {
            relPath = filePath.replace(workspaceFolder.uri.fsPath + '/', '');
        }

        const panel = vscode.window.createWebviewPanel(
            'evotraceFileHistory',
            `EvoTrace: ${relPath.split('/').pop()}`,
            vscode.ViewColumn.Two,
            { enableScripts: true }
        );

        panel.webview.html = await buildFileHistoryHtml(serverUrl, projectKey, relPath);
    });

    // Command: Show project dashboard
    const dashboardCmd = vscode.commands.registerCommand('evotrace.showProjectDashboard', async () => {
        const config = vscode.workspace.getConfiguration('evotrace');
        const serverUrl = config.get('serverUrl', 'http://43.155.130.69');
        const projectKey = config.get('defaultProject', 'maidao_merchant');

        const panel = vscode.window.createWebviewPanel(
            'evotraceDashboard',
            'EvoTrace 项目面板',
            vscode.ViewColumn.One,
            { enableScripts: true }
        );

        panel.webview.html = await buildDashboardHtml(serverUrl, projectKey);
    });

    // Status bar item
    const statusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
    statusBar.text = '$(history) EvoTrace';
    statusBar.tooltip = '点击打开 EvoTrace 项目面板';
    statusBar.command = 'evotrace.showProjectDashboard';
    statusBar.show();

    context.subscriptions.push(fileHistoryCmd, dashboardCmd, statusBar);
}

async function fetchApi(serverUrl, path) {
    return new Promise((resolve, reject) => {
        const url = new URL(path, serverUrl);
        const client = url.protocol === 'https:' ? https : http;
        client.get(url.toString(), (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try { resolve(JSON.parse(data)); }
                catch { resolve({ error: 'parse failed', raw: data }); }
            });
        }).on('error', reject);
    });
}

async function buildFileHistoryHtml(serverUrl, projectKey, filePath) {
    let historyHtml = '<p style="color:#909399">加载中…</p>';

    try {
        const resp = await fetchApi(serverUrl,
            `/api/v1/files/history?path=${encodeURIComponent(filePath)}&projectKey=${encodeURIComponent(projectKey)}`);

        if (resp.success && resp.data && resp.data.length > 0) {
            historyHtml = resp.data.map(e => `
                <div class="event">
                    <div class="event-head">
                        <span class="event-type">${e.eventType}</span>
                        <code>${e.commitSha || '-'}</code>
                        <span class="author">${e.author || '-'}</span>
                        <span class="time">${e.occurredAt}</span>
                    </div>
                    <div class="changes">${e.changeKind}: +${e.addLines} / -${e.delLines} 行</div>
                    ${e.summary ? `<div class="summary">🤖 AI: ${e.summary}</div>` : ''}
                </div>
            `).join('');
        } else {
            historyHtml = '<p style="color:#909399">该文件暂无演化记录</p>';
        }
    } catch (e) {
        historyHtml = `<p style="color:#f56c6c">无法连接 EvoTrace 服务: ${serverUrl}</p>`;
    }

    return `<!DOCTYPE html><html><head><meta charset="utf-8">
        <style>
            body { font-family: var(--vscode-font-family); padding: 16px; color: var(--vscode-foreground); background: var(--vscode-editor-background); }
            h3 { margin-top: 0; }
            .event { padding: 12px; margin-bottom: 8px; border: 1px solid var(--vscode-panel-border); border-radius: 6px; }
            .event-head { display: flex; gap: 8px; align-items: center; margin-bottom: 4px; }
            .event-type { font-weight: 600; color: var(--vscode-textLink-foreground); }
            code { background: var(--vscode-textCodeBlock-background); padding: 1px 6px; border-radius: 3px; font-size: 12px; }
            .author { color: var(--vscode-descriptionForeground); }
            .time { color: var(--vscode-descriptionForeground); font-size: 12px; margin-left: auto; }
            .changes { color: var(--vscode-descriptionForeground); margin: 4px 0; }
            .summary { color: var(--vscode-foreground); background: var(--vscode-textBlockQuote-background); padding: 8px; border-radius: 4px; margin-top: 6px; }
        </style></head><body>
        <h3>📄 ${filePath}</h3>
        ${historyHtml}
        <p style="font-size:12px;color:var(--vscode-descriptionForeground);margin-top:16px">
            EvoTrace — 系统演化追踪与智能分析平台
        </p>
    </body></html>`;
}

async function buildDashboardHtml(serverUrl, projectKey) {
    let statsHtml = '<p style="color:#909399">加载中…</p>';

    try {
        const [stats, hotspots] = await Promise.all([
            fetchApi(serverUrl, '/api/v1/dashboard/stats'),
            fetchApi(serverUrl, `/api/v1/projects/${projectKey}/analysis/hotspots?days=30`)
        ]);

        if (stats.success) {
            const d = stats.data;
            statsHtml = `
                <div class="stats-grid">
                    <div class="stat-card"><span class="stat-num">${d.projectCount}</span>项目</div>
                    <div class="stat-card"><span class="stat-num">${d.appCount}</span>应用</div>
                    <div class="stat-card"><span class="stat-num">${d.todayChanges}</span>今日变更</div>
                    <div class="stat-card"><span class="stat-num">${d.releaseCount}</span>版本</div>
                </div>`;
        }

        if (hotspots.success && hotspots.data && hotspots.data.topChangedFiles) {
            statsHtml += `<h4>🔥 热点文件 (30天)</h4>`;
            statsHtml += hotspots.data.topChangedFiles.slice(0, 10).map(f =>
                `<div class="hotspot">${f.filePath} — ${f.changeCount} 次变更</div>`
            ).join('');
        }
    } catch (e) {
        statsHtml = `<p style="color:#f56c6c">无法连接 EvoTrace 服务: ${serverUrl}</p>`;
    }

    return `<!DOCTYPE html><html><head><meta charset="utf-8">
        <style>
            body { font-family: var(--vscode-font-family); padding: 16px; color: var(--vscode-foreground); background: var(--vscode-editor-background); }
            .stats-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 20px; }
            .stat-card { text-align: center; padding: 16px; border: 1px solid var(--vscode-panel-border); border-radius: 8px; }
            .stat-num { display: block; font-size: 28px; font-weight: 700; color: var(--vscode-textLink-foreground); }
            h4 { margin: 16px 0 8px; }
            .hotspot { padding: 6px 8px; border-bottom: 1px dashed var(--vscode-panel-border); font-size: 13px; }
        </style></head><body>
        <h3>📊 ${projectKey}</h3>
        ${statsHtml}
        <p style="font-size:12px;color:var(--vscode-descriptionForeground);margin-top:16px">
            右键任意文件 → "EvoTrace: 查看文件演化历史"
        </p>
    </body></html>`;
}

function deactivate() {}

module.exports = { activate, deactivate };
