# TokenPro v1.3.7

- macOS 连接或切换 Codex、Claude 通道时不再通过 AppleScript 控制客户端或 Terminal。
- 客户端重连改用同用户进程退出和 `open -a` 启动，命令行使用权限为 `0700`、执行后自删除的临时 `.command` 文件。
- 连接模型通道不再申请 macOS“自动化”权限。
- Windows 继续使用 Codex 官方支持的 `windows.sandbox = "unelevated"` 非管理员模式。
- 连接 TokenPro 时直接移除其他 Codex 中转的当前路由、冲突的 `custom` 服务商和 `openai_base_url`，不弹出确认，也不保留中转配置备份。
- 清理不卸载对方应用，不删除 Codex 登录、项目、MCP、权限和非活动服务商配置。
- 应用自身写入受保护安装目录时的更新提权逻辑保持不变。
