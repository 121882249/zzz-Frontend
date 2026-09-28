# TokenPro v1.3.54

- TokenPro 渠道启动时自动刷新 `imagegen` Skill，升级后无需重新应用模型。
- 切换到 Codex 官方渠道时停用 TokenPro 图片 Skill，改由 Codex 原生图片工具处理。
- 防止官方渠道恢复旧的兼容性图片 Skill 后再次抢占生图请求。
- 更新 README，明确 TokenPro 与官方渠道的生图路径。
- 通过 Java 回归测试，`556 checks passed`。
