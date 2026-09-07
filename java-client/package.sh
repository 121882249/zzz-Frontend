#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./build.sh
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
if [ -z "$JAVA_HOME" ] && [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then
  JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
VERSION="1.0.0"
OS="$(uname -s)"
ARCH="$(uname -m)"
rm -rf dist
mkdir -p dist
case "$OS" in
  Darwin) TYPE=dmg ;;
  Linux) TYPE=deb ;;
  *) echo "Use package.ps1 on Windows." >&2; exit 1 ;;
esac
"$JAVA_HOME/bin/jpackage" --type "$TYPE" --name TokenPro --app-version "$VERSION" \
  --input build --main-jar TokenPro.jar --main-class work.tokenpro.client.Main \
  --add-modules java.base,java.desktop,java.net.http,jdk.httpserver \
  --vendor TokenPro --description "TokenPro cross-platform desktop client" --dest dist
echo "Created TokenPro $VERSION for $OS/$ARCH in java-client/dist"
