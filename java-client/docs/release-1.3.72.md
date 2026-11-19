# v1.3.72

- 全局 Key 的 ImageGen Skill 跟随当前 Codex 文本分组生图：路由仍是该文本分组的 `tp-gNN-...`，请求体模型固定为 `gpt-image-2`，不再改走已保存的生图分组。
- 仅当当前 Codex 模型本身是生图分组路由（例如 `tp-g65-gpt-image-2`）时，才使用该生图分组和对应图片模型。
