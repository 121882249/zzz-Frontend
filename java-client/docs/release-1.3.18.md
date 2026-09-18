# TokenPro v1.3.18

- Codex 渠道切换改用系统内置的 `openai` provider；TokenPro 只配置自己的 API 地址、全局 Key 和分组模型目录。
- 移除长期驻留的 `[model_providers.custom]` 路由，避免切换到 TeamoRouter 后旧会话仍暗中请求 TokenPro。
- 停止扫描、恢复和批量改写历史对话；切换只维护认证、模型目录和当前渠道配置，减少 macOS 目录授权与 Windows 权限干扰。
- TokenPro 模型仍使用 `tp-g<分组 ID>-<模型>` 路由格式，全局 Key 请求可稳定携带后端所需的分组信息。
