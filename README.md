# TokenPro 跨平台桌面客户端

安装包可从仓库的 [GitHub Releases](https://github.com/121882249/TokenPro-Frontend/releases) 页面下载。

TokenPro 现在只有一套 Java 21/Swing 客户端源码，支持 Windows、macOS 和 Linux。macOS 的 Intel 与 Apple 芯片版本、Windows x64 版本和 Linux x64 版本由 GitHub Actions 分别在对应系统构建。

## 功能

- TokenPro 邮箱密码登录与本机会话恢复。
- 登录后的“我的账户”仅显示账户余额和退出账户操作。
- 从模型广场选择 Codex 模型，自动创建专用 Key、应用配置，并可一键恢复官方配置。
- 通过纯 Java 本地桥接把 TokenPro 模型接入 Claude Desktop，支持模型分组自动切换、工具调用和流式响应。
- API Key 保存在当前系统用户的 TokenPro 私有配置目录，不写入 `config.toml`。
- “我的应用”提供“选择模型 / 恢复官方配置 / 连接客户端”；命令行工具提供下载入口和连接命令行。
- 在 TokenPro 内置浏览器中打开后台管理、使用文档、充值页和版本下载页。
- 启动后自动检查更新并提醒，也可手动检查；支持启动 Codex、Claude、Codex CLI 与 Claude Code。

## 构建

需要 JDK 21。macOS 或 Linux：

```bash
./build.sh
./release.sh
```

Windows PowerShell：

```powershell
./build.ps1
./release.ps1
```

详细的平台目录与打包说明见 [`java-client/README.md`](java-client/README.md)。

## 源码

Java 源码位于 `java-client/src/main/java/work/tokenpro/client/`。入口类是 `work.tokenpro.client.Main`。
