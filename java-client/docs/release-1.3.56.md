# TokenPro v1.3.56

- TokenPro 文本组和 GPT 生图组统一改用 `model_provider = "openai"` 与 `openai_base_url` TokenPro 路由。
- 保留全局 Key 和按分组限定的模型路由。
- TokenPro 连接时使用本地图片 Skill 调用 TokenPro 图片接口，避免图片请求串到官方 provider。
- 官网渠道继续恢复 Codex 官方原生图片工具。
- 通过 Java 自测和纯生图原生路由集成测试。
