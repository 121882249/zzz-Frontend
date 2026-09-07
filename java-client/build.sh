#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
if [ -z "$JAVA_HOME" ] && [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then
  JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
  echo "JDK 21 is required. Set JAVA_HOME to a JDK 21 installation." >&2
  exit 1
fi
rm -rf build/classes build/TokenPro.jar
mkdir -p build/classes
find src/main/java -name '*.java' -print0 | xargs -0 "$JAVA_HOME/bin/javac" --release 21 --add-modules jdk.httpserver -encoding UTF-8 -d build/classes
cat > build/manifest.mf <<'EOF'
Main-Class: work.tokenpro.client.Main
Implementation-Title: TokenPro
Implementation-Version: 1.0.0

EOF
"$JAVA_HOME/bin/jar" --create --file build/TokenPro.jar --manifest build/manifest.mf -C build/classes .
"$JAVA_HOME/bin/java" -jar build/TokenPro.jar --self-test
echo "Built java-client/build/TokenPro.jar"
