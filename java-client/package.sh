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
VERSION="1.2.17"
OS="$(uname -s)"
ARCH="$(uname -m)"
rm -rf dist
mkdir -p dist
case "$OS" in
  Darwin) TYPE=dmg; ICON=(--icon ../Router.icns) ;;
  Linux) TYPE=deb; ICON=(--icon ../Resources/TokenProCosmosIcon.png) ;;
  *) echo "Use package.ps1 on Windows." >&2; exit 1 ;;
esac
rm -rf build/input
mkdir -p build/input
cp build/TokenPro.jar build/input/TokenPro.jar
"$JAVA_HOME/bin/jpackage" --type "$TYPE" --name TokenPro --app-version "$VERSION" \
  --input build/input --main-jar TokenPro.jar --main-class work.tokenpro.client.Main \
  --module-path "$JAVA_HOME/jmods" \
  --add-modules java.base,java.desktop,java.net.http,jdk.httpserver,jdk.crypto.ec \
  --vendor TokenPro --description "TokenPro cross-platform desktop client" "${ICON[@]}" --dest dist
echo "Created TokenPro $VERSION for $OS/$ARCH in java-client/dist"
