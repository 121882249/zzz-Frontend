# v1.3.71

- 切换或添加 Codex 模型时，若 `config.toml` 的 TokenPro 标记不成对，先备份再自动修复，不再弹出「操作未完成」。
- 含 `/` 的模型 id（例如 `deepseek/deepseek-v4.1-flash`）按完整字符串写入路由。
- 只有配置真正无法解析时才拒绝写入，并提示从 `config.toml.tokenpro-backup-*` 恢复，或移走损坏文件后重新选择模型。
