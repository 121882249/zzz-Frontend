# TokenPro v1.3.55

- 修复纯生图模型被错误交给 Skill CLI、无法稳定发起生图请求的问题。
- 纯生图模型现在使用 Codex 原生图片路由和 `native-v2` Responses provider。
- 混合文本与生图模型继续使用本地 `imagegen` Skill CLI。
- 通过 556 项 Java 自测和纯生图原生路由集成测试。
