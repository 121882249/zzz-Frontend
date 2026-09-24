# TokenPro v1.3.52

- 修复 Windows Codex 在切换后提示 `invalid transport in mcp_servers.computer-use` 的问题。
- 不再创建、补全或强制启用 `computer-use` MCP；该配置完全交由 Codex 官方插件管理。
- 继续保留 `localeOverride = "zh-CN"`，且不修改普通插件。
- 通过 553 项 Java 回归测试。
