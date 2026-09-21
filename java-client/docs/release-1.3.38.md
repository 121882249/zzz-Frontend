# TokenPro v1.3.38

- 连接 TokenPro 时停用会抢占图片请求的 `teamorouter-imagegen` Skill，并将变更纳入渠道切换备份和失败回滚。
- Codex 命令行只写入和显示非生图模型，不再从 Codex 客户端的已选模型中重新合并生图组。
- Codex 命令行卡片中的已选模型数量按过滤后的非生图模型计算。
- Codex 客户端仍保留独立生图模型选择和原生图片路由。
