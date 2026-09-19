# TokenPro v1.3.27

- Claude Desktop 与 Claude CLI 重新选择模型时，模型选择器只显示当前选择的模型。
- 同一 TokenPro 账户曾使用的 Claude 模型路由会作为隐藏兼容路由保留，旧对话可继续调用原模型。
- 隐藏路由在切回 Claude 官方配置后仍保留，再切回 TokenPro 时自动恢复兼容。
- 路由历史仅保存模型与分组信息，不保存聊天内容；不同 TokenPro 账户之间不会共用。
- 路由历史损坏时直接忽略，不阻止切换渠道。

验证：532 项自检通过，macOS Apple 芯片完整安装包构建通过。
