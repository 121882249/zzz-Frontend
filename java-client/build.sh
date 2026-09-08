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
FX_VERSION="21.0.2"
case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) FX_PLATFORM="osx-aarch64" ;;
  Darwin-x86_64) FX_PLATFORM="osx-x64" ;;
  Linux-*) FX_PLATFORM="linux-x64" ;;
  *) echo "Unsupported JavaFX platform: $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac
FX_HOME="build/javafx-$FX_VERSION-$FX_PLATFORM"
if [ ! -f "$FX_HOME/lib/javafx.web.jar" ]; then
  archive="build/openjfx-$FX_VERSION-$FX_PLATFORM.zip"
  mkdir -p build
  curl -fL "https://download2.gluonhq.com/openjfx/$FX_VERSION/openjfx-${FX_VERSION}_${FX_PLATFORM}_bin-sdk.zip" -o "$archive"
  unzip -q -o "$archive" -d build/javafx-unpack
  mkdir -p "$FX_HOME"
  cp -R build/javafx-unpack/javafx-sdk-$FX_VERSION/. "$FX_HOME/"
fi
rm -rf build/classes build/TokenPro.jar
mkdir -p build/classes
find src/main/java -name '*.java' -print0 | xargs -0 "$JAVA_HOME/bin/javac" --release 21 --module-path "$FX_HOME/lib" --add-modules javafx.controls,javafx.web,javafx.swing,jdk.httpserver -encoding UTF-8 -d build/classes
mkdir -p build/classes/assets
cp ../Resources/TokenProCosmosIcon.png build/classes/assets/TokenProCosmosIcon.png
cp ../Resources/LoginCosmos-v2.png build/classes/assets/LoginCosmos-v2.png
cp ../Resources/ModelUniverseVortex.png build/classes/assets/ModelUniverseVortex.png
cp ../Resources/OpenAIBlossomRuntime.png build/classes/assets/OpenAIBlossomRuntime.png
cp ../Resources/ClaudeSparkRuntime.png build/classes/assets/ClaudeSparkRuntime.png
cp ../Resources/GeminiSparkTransparent.png build/classes/assets/GeminiSparkTransparent.png
cp ../Resources/GrokMarkTransparent.png build/classes/assets/GrokMarkTransparent.png
cp ../Resources/UnknownModelRuntime.png build/classes/assets/UnknownModelRuntime.png
cp ../Resources/CodexOriginal.png build/classes/assets/CodexOriginal.png
cp ../Resources/ClaudeOriginal.png build/classes/assets/ClaudeOriginal.png
cp ../Resources/SparklesLucide.png build/classes/assets/SparklesLucide.png
cp ../Resources/CircleUserLucide.png build/classes/assets/CircleUserLucide.png
cp ../Resources/CircleUserPurple.png build/classes/assets/CircleUserPurple.png
cp ../Resources/WebCog.png build/classes/assets/WebCog.png
cp ../Resources/WebBook.png build/classes/assets/WebBook.png
cp ../Resources/RefreshCwLucide.png build/classes/assets/RefreshCwLucide.png
cp ../Resources/PlusLucide.png build/classes/assets/PlusLucide.png
cat > build/manifest.mf <<'EOF'
Main-Class: work.tokenpro.client.Main
Implementation-Title: TokenPro
Implementation-Version: 1.1.4

EOF
"$JAVA_HOME/bin/jar" --create --file build/TokenPro.jar --manifest build/manifest.mf -C build/classes .
"$JAVA_HOME/bin/java" --module-path "$FX_HOME/lib" --add-modules javafx.controls,javafx.web,javafx.swing -jar build/TokenPro.jar --self-test
echo "Built java-client/build/TokenPro.jar"
