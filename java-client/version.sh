#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
version="$(sed -nE 's/.*public static final String VERSION = "([0-9]+\.[0-9]+\.[0-9]+)";.*/\1/p' src/main/java/work/tokenpro/client/Main.java)"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Invalid Main.VERSION' >&2; exit 1; }
if [[ "${GITHUB_REF_TYPE:-}" == tag && "${GITHUB_REF_NAME:-}" != "v$version" ]]; then
  echo 'Release tag does not match Main.VERSION' >&2; exit 1
fi
printf '%s\n' "$version"
