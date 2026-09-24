# TokenPro v1.3.53

- 启动 TokenPro 时自动移除旧版 `mcp_servers.computer-use` 中不被当前 Codex 接受的 `transport` 字段。
- 修复仅改动该字段，保留 Computer Use 的命令、参数、启用状态及其他 MCP/插件配置。
- 继续保持中文桌面 locale，不更改模型和全局 Key 路由。
- 通过 Java 回归测试。
