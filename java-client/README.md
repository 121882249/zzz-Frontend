# TokenPro Java 跨平台客户端

同一套 Java 21/Swing 源码支持 Windows、macOS 和 Linux。安装包必须在目标系统和目标芯片上构建；`jpackage` 会把 Java 运行时一起放进安装包，最终用户无需单独安装 Java。

## 当前功能

- TokenPro 邮箱密码登录和登录状态恢复。
- 在首页弹窗按可用分组多选 Codex / Claude 模型，使用账户的 TokenPro 全局 Key 应用配置并打开客户端。
- 安全地保存 API Key，通过 Codex command-backed authentication 提供凭据。
- 备份、接入和一键恢复 Codex 官方配置。
- 读取模型广场，并将选择的模型接入 Claude Desktop。
- 登录后的“我的账户”仅显示账户余额和退出账户操作。
- 纯 Java 本地桥接仅监听 `127.0.0.1`，支持 Anthropic Messages、OpenAI Responses、工具调用和 SSE 流式响应。
- Claude 桥接复用 TokenPro 全局 Key，并按当前选中的模型动态路由，无需切换 Key 分组。
- 桌面客户端提供“选择模型 / 恢复官方配置 / 打开应用”；命令行客户端提供下载入口和打开命令行。
- 使用系统默认浏览器打开后台管理、使用文档、充值页和 GitHub 版本页。
- 内置 GitHub Releases 更新检查。
- 配置目录自动适配 `%APPDATA%`、macOS Application Support 和 Linux XDG。

Claude 桥接配置和上游凭据保存在当前用户的 TokenPro 配置目录。Claude Desktop 只获得随机生成的本机桥接令牌，不会读取上游 API Key。

## 构建

安装 JDK 21 后：

```bash
cd java-client
./build.sh
java -jar build/TokenPro.jar
```

Windows PowerShell：

```powershell
cd java-client
./build.ps1
& "$env:JAVA_HOME/bin/java.exe" -jar build/TokenPro.jar
```

## 原生安装包

- macOS Intel：在 Intel Mac 上运行 `./package.sh`。
- macOS Apple 芯片：在 Apple Silicon Mac 上运行 `./package.sh`。
- Windows x64/ARM64：在相应芯片的 Windows 机器上运行 `./package.ps1`。
- Linux：在 Linux 构建机运行 `./package.sh`。

GitHub Actions 会分别构建 Windows x64、macOS Intel、macOS Apple 芯片和 Linux x64 产物。Windows ARM64 可以在 ARM64 Windows 自托管 Runner 上使用相同脚本构建。
