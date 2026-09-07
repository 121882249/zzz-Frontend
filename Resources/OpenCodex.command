#!/bin/zsh
set -e

if command -v codex >/dev/null 2>&1 && [[ "$(command -v codex)" != *"/Applications/ChatGPT.app/"* ]] && [[ "$(command -v codex)" != *"/Applications/Codex.app/"* ]]; then
  exec codex
fi

echo "未找到 Codex CLI，请先安装后重试。"
read -k 1 "?按任意键关闭…"
