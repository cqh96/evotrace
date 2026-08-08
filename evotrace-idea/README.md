# EvoTrace IntelliJ IDEA 插件

在 IDEA 中查看文件演化历史与项目面板，对接 EvoTrace Server。

## 功能

- 编辑器 / 项目树右键 → **EvoTrace: 查看文件演化历史**
- Tools 菜单 / 状态栏 **EvoTrace** → 打开项目面板
- Settings → Tools → **EvoTrace**：配置 `serverUrl`、`projectKey`、登录账号

默认：`http://43.155.130.69` / `maidao_merchant` / `admin` / `admin123`  
控制台 API 需要 JWT，插件会自动登录。

## 构建

需要 JDK 17+、网络（下载 IntelliJ Platform SDK）。

```bash
cd evotrace-idea
./gradlew buildPlugin
```

产物：`build/distributions/evotrace-idea-0.1.0.zip`

## 安装

1. IDEA → Settings → Plugins → ⚙️ → **Install Plugin from Disk…**
2. 选择上面的 zip
3. 重启 IDEA

## 开发调试

```bash
./gradlew runIde
```

会启动一个带本插件的沙箱 IDEA。
