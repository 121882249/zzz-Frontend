#!/bin/zsh
set -e

if command -v claude >/dev/null 2>&1; then
  exec claude
elif [[ -x "$HOME/.local/bin/claude" ]]; then
  exec "$HOME/.local/bin/claude"
elif [[ -x "$HOME/.claude/local/claude" ]]; then
  exec "$HOME/.claude/local/claude"
fi

echo "未找到 Claude Code，请先安装后重试。"
read -k 1 "?按任意键关闭…"
