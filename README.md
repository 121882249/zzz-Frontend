# TokenPro 原生客户端 v0.4.3

macOS 原生 SwiftUI 客户端，面向 Intel 与 Apple Silicon / macOS 14+。应用接入 TokenPro 账户后管理 Codex 与 Claude 桌面版模型连接。客户端不创建任务或聊天。

## 当前功能

- 原生 TokenPro 邮箱密码登录、注册、邮箱验证码、邀请码和动态两步验证。
- 登录会话保存在 TokenPro 本机私有目录；启动时自动恢复并续期，主动退出后停止恢复。
- 首页右上角显示当前登录用户邮箱，点击可进入“我的账户”。
- 查看真实账户余额和已有 API 令牌，将账户令牌导入为模型连接。
- 首页钱包显示 `1￥ = 1$` 充值比例；点击“充值”使用客户端当前登录账户打开内置充值页，账户、余额和订单一一对应。
- Stripe 支付结果以服务器订单状态为准；客户端会拦截支付页的提前成功提示并转到订单结果页核验。
- 从 TokenPro 账户令牌生成模型连接，并在首页切换当前模型。
- 将所选连接直接写入 Codex 自定义模型服务商配置，无本地代理、无监听端口。
- API Key 保存在 TokenPro 本机私有目录。Codex 请求前通过 TokenPro 凭据助手读取对应 Key，Codex 配置文件不包含明文 Key。
- Claude 客户端卡片可跨有效分组勾选 GPT、Grok、Claude、Gemini 对话模型；保存后出现在 Claude 桌面版自己的模型菜单中。
- Claude 只使用一把 `TokenPro · Claude` Key。内置服务按请求模型自动更新这把 Key 的 `group_id`，确认后转发请求；锁持续到回答结束，跨分组请求依次处理。
- 打开已安装的 Codex、Claude 桌面客户端，以及单独安装的 Codex CLI、Claude Code 命令行工具。应用内置的辅助可执行文件不会被误判为已安装的 CLI。
- “后台管理”在客户端内置网页窗口打开 `https://tokenpro.work/admin/dashboard`，并使用客户端当前登录账户。
- 右上角自动检查版本：最新版本显示绿色；有更新时显示黄色并附红色叹号。

## 使用方法

1. 登录 TokenPro；已登录用户下次打开会自动登录。
2. 在“我的账户”选择一个已有 API 令牌，填写或获取模型后保存。
3. 在首页选择连接，点击“接入 Codex”。
4. 重新打开 Codex 后生效。TokenPro 可以关闭，不需要后台运行。
5. 切换连接或模型后点击“应用当前连接”，再重新打开 Codex。

接口地址应填写支持 OpenAI Responses API 的基础地址，例如 `https://example.com/v1`。模型可用性、工具调用能力和计费由所选中转站决定。

## 安全与存储

- 登录密码只用于提交，不写入本机。
- 登录会话：`~/Library/Application Support/TokenPro/account-session.json`。
- Codex 模型令牌：同目录 `codex-model-token`；旧连接令牌位于同目录的 `credentials/`。
- Claude 本地连接令牌：同目录 `claude-model-token`，仅用于认证本机端口；唯一的上游 Key、账户编号和模型路由保存在 `claude-bridge.json`，权限为 0600。Claude 配置中不写入上游 Key。
- 凭据目录权限为 `0700`，凭据文件权限为 `0600`，仅当前 macOS 用户可读写。
- 本地非密钥连接配置：`~/Library/Application Support/TokenPro/connections.json`。
- Codex 原配置备份：同目录 `codex-backup.json`。切换连接时从原始备份重新生成配置，避免叠加修改。
- 客户端不访问 macOS 钥匙串，重新构建和 Codex Hook 调用不会触发钥匙串授权。
- Codex 请求仍直接发送至选中的接口地址。Claude 请求经过内置的本机服务，转发目标固定为 `https://tokenpro.work`；诊断不记录消息正文、工具参数或 Key。

## Codex 配置方式

应用写入自定义 `model_provider`，其中 `base_url` 为所选连接的真实 HTTPS 地址，`wire_api = "responses"`。认证采用 Codex 支持的 command-backed authentication：Codex 调用应用内凭据助手读取本机私有凭据并把 Bearer 令牌输出到标准输出。

如果接入后的 Codex 配置被其他程序修改，应用会停止自动覆盖以保护外部改动。移动 TokenPro.app 后，配置中的凭据助手路径需要重新点击“应用当前连接”更新。

## 云端接口

- 账户 API：`https://tokenpro.work/api/v1`
- 公共配置：`GET /settings/public`
- 登录：`POST /auth/login`
- 注册：`POST /auth/register`
- 会话续期：`POST /auth/refresh`
- 账户：`GET /auth/me`
- 令牌：`GET /keys`、`GET /keys/{id}`
- 更新清单：`https://tokenpro.work/client-updates/macos.json`

## 构建与验证

```bash
./build.sh
"build/TokenPro.app/Contents/MacOS/TokenPro" --account-tests
```

核心连接与配置：`Sources/Core.swift`、`Sources/App.swift`。账户与登录：`Sources/Account.swift`、`Sources/LoginView.swift`。

Codex 自定义模型服务商和命令式认证参考：<https://learn.chatgpt.com/docs/config-file/config-advanced#custom-model-providers>

Claude 桌面第三方推理参考：<https://claude.com/docs/third-party/claude-desktop/gateway>

## Claude 桌面版配置

- 本地配置档位于 `~/Library/Application Support/Claude-3p/configLibrary/`。TokenPro 新建自己的配置档，并保留用户已有配置档。
- Claude 连接 `http://127.0.0.1:23179`（仅本机），内置服务将请求发送到 `https://tokenpro.work`。GPT 分组适配 Responses，其他分组使用现有 Messages 兼容接口；模型列表来自定价与当前账户有效分组。
- 首次接入或修改模型列表后，打开 Claude 时重新加载一次配置；之后在 Claude 内切换模型不会重启，TokenPro 的打开按钮只唤起已有进程。
- 左下角显示 TokenPro，隐藏本地 OS 用户名前缀。
- 后台 token 预估在本机计算并标记 `tokenpro_estimated`，用于桌面上下文预览；避免桌面回退为大量收费的试探性请求。它不是账单用量，实际输入、缓存和输出用量仍来自上游响应。
- “恢复官方配置”会应用一个空的官方配置档，使 Claude 回到标准模式；不会修改 `~/Library/Application Support/Claude/` 下的登录、历史和普通设置。

## 模型身份与输入用量

- 每个所选模型的目录条目携带独立的请求名称和分组 ID；切换模型时随对应指令更新。Codex 是应用名称，不能据此推断底层模型。请求别名也不是上游模型真实性的证明。
- 问候和已有信息可以回答的问题优先直接回答，避免无意义地查文件或搜索。实际行为仍受 Codex 的其他指令、已安装技能和模型能力影响。
- 输入用量包括运行说明、工具定义、技能目录及历史，不只计算最后一条用户消息。此修改减少不必要的后续调用，不移除 Codex 自带工具，也不保证降低首次请求的基础输入。
- 模型选择窗口的“精简上下文”默认不勾选，并显示警告标识；主动开启后：技能目录预算最多 1,200 tokens（保留用户设置的更低预算），关闭外部应用连接工具（`features.apps = false`）。终端、文件编辑及独立配置的 MCP 不受此开关关闭。可取消勾选并保存恢复原设置；“恢复官方配置”会切回内置 OpenAI 服务，并撤销精简设置。配置以逐项标记保存原值，恢复时保留用户后续编辑。
- 技能目录可能随后续请求继续发送，实际读取的技能全文也可能进入历史；该预算不是整段对话的 token 上限。缓存命中只影响计费方式，不代表不发送。更改后使用新任务验证，旧任务仍可能保留已经加载的说明。
- 启动 Codex 并刷新配置成功后静默完成；失败继续提示原因。
- 已恢复官方配置时，更新 TokenPro 不重新启用模型接入；用户再次选择并保存模型后，新目录才生效。

## 恢复官方配置

“恢复官方配置”不再恢复接入前可能存在的第三方服务。它选择内置 `openai` 服务，移除自定义服务商定义、模型目录、模型/审核模型覆盖、官方地址覆盖以及默认 profile；保留项目、插件与其他用户设置。只移除 TokenPro 的 Hook 处理器，同组其他处理器保留。

已有 `auth.json`、官方钥匙串、聊天历史、TokenPro 账户登录均不清除。缺少 `.codex` 或 `config.toml` 时自动创建最小官方配置；不生成或伪造登录凭据。首次登录仍由官方 Codex 完成。更改前的配置和 Hook 另存到 `before-official-<UUID>` 私有备份目录，原有接入备份也保存在其中。此操作影响配置默认值，不改写既有聊天的模型选择。

## Mac 通用版打包

`./build.sh` 默认生成包含 arm64 与 x86_64 的 Universal 应用；也可用第二个参数单独指定架构。`release.sh` 输出一个通用 DMG，Intel 与 Apple 芯片的更新清单指向同一安装包。Windows 项目位于相邻的 `TokenProWindows`，独立打包，当前为未经过 Windows 实机验证的预览版。
