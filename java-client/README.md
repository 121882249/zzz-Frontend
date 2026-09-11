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
- “我的应用”提供“选择模型 / 恢复官方配置 / 连接客户端”；命令行工具提供下载入口和连接命令行。
- 使用系统默认浏览器打开后台管理、使用文档、充值页和 GitHub 版本页。
- 内置 GitHub Releases 更新检查。
- 配置目录自动适配 `%APPDATA%`、macOS Application Support 和 Linux XDG。

Claude 桥接配置和上游凭据保存在当前用户的 TokenPro 配置目录。Claude Desktop 只获得随机生成的本机桥接令牌，不会读取上游 API Key。

## 构建

### 命令行模型选择

命令行卡片提供与客户端一致的“模型选择”菜单和独立“已选 N 个模型”状态；先应用模型，再点击“连接命令行”。
再次连接会沿用已保存的 CLI 选择，不覆盖客户端配置，也不会关闭已运行的 Codex/Claude。
Codex 使用客户端同一套排序规则生成独立的模型目录及 priority 顺序；Claude 使用同一排序生成
`claude-cli-settings.json`，通过 `--settings` 设置内部 `/model` 列表。
Claude 仅包含非生图模型，要求 Claude Code 2.1.242 或更新版本（支持 `modelPicker`）。
订阅优先级、余额排序、厂商顺序及组内价格排序均沿用客户端逻辑。
Claude 命令行通过本地桥接和凭据 helper 连接，不修改用户的全局 Claude settings.json。
Windows 上该连接流程要求原生 CLI；WSL 环境需要单独配置。

命令行配置位于 TokenPro 数据目录的 `cli/codex` 和 `cli/claude` 子目录，选择记录和备份也独立保存。
从 TokenPro 启动时，Codex CLI 的 `CODEX_HOME` 指向 `cli/codex/home`，Claude CLI 的
`CLAUDE_CONFIG_DIR` 指向 `cli/claude/home`。仅向新终端传入变量，不修改系统环境。
Claude Desktop、Codex Desktop 生图、Claude CLI、Codex CLI 生图分别使用 23179、23180、23181、23182，拥有独立路由、令牌和 helper。
桌面端重新选模型、恢复配置或关闭桥接，不会覆盖命令行配置，反之亦然。
普通终端可使用模型菜单中的“复制独立启动命令”，运行 TokenPro 数据目录下的
`bin/tokenpro-codex` 或 `bin/tokenpro-claude`（Windows 为 .cmd）。该入口在运行前恢复对应桥接并加载独立配置。
直接输入系统原来的 `codex`/`claude` 仍采用原生配置；TokenPro 不覆盖它们，也不修改全局 PATH。

### 思考强度与更新恢复

Codex 的桌面端和 CLI 共用模型目录导出逻辑，保留本机原生模型声明的 Ultra 及快速服务档位。
重新应用模型时保留该模型仍支持的思考强度和服务档位；不为新配置默认开启付费加速。
更新前暂停已运行的四类本地桥接，立即更新失败则恢复原有连接；程序重新启动时分别恢复已配置桥接。
模型推理参数和快速档位是否生效仍由实际客户端版本、上游模型及账户权限决定。

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
