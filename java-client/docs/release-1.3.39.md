# TokenPro v1.3.39

- 修复 Codex 原生图片工具调用失败导致的 `unsupported call: image_genimagegen`。
- Codex 使用专用 Responses provider 保留 `native-v2` 图片路由、全局 Key 和按回合分组隔离。
- 同时兼容明文与 Base64 形式的 `tp-g<分组>-<模型>` 路由 ID。
- 混合对话模型、纯生图模型和图片返回链路均补充真实 Codex 隔离测试。
