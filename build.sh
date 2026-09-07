#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
DEST="${1:-build}"
ARCH="${2:-universal}"
case "$ARCH" in arm64|x86_64|universal) ;; *) echo "Unsupported architecture: $ARCH" >&2; exit 1 ;; esac
mkdir -p "$DEST/TokenPro.app/Contents/MacOS" "$DEST/TokenPro.app/Contents/Resources"
if [ "$ARCH" = universal ]; then
  for CPU in arm64 x86_64; do
    swiftc -swift-version 5 -O -target "$CPU-apple-macosx14.0" Sources/*.swift -o "$DEST/.TokenPro-$CPU" -framework SwiftUI -framework AppKit -framework WebKit
  done
  lipo -create "$DEST/.TokenPro-arm64" "$DEST/.TokenPro-x86_64" -output "$DEST/TokenPro.app/Contents/MacOS/TokenPro"
  rm "$DEST/.TokenPro-arm64" "$DEST/.TokenPro-x86_64"
else
  swiftc -swift-version 5 -O -target "$ARCH-apple-macosx14.0" Sources/*.swift -o "$DEST/TokenPro.app/Contents/MacOS/TokenPro" -framework SwiftUI -framework AppKit -framework WebKit
fi
cat > "$DEST/TokenPro.app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>TokenPro</string>
<key>CFBundleIdentifier</key><string>work.tokenpro.mac</string>
<key>CFBundleName</key><string>TokenPro</string>
<key>CFBundleDisplayName</key><string>TokenPro</string>
<key>CFBundleIconFile</key><string>Router.icns</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>0.4.3</string>
<key>CFBundleVersion</key><string>13</string>
<key>LSMinimumSystemVersion</key><string>14.0</string>
<key>NSHighResolutionCapable</key><true/>
<key>NSPrincipalClass</key><string>NSApplication</string>
</dict></plist>
PLIST
cp Resources/TokenProBridge.js "$DEST/TokenPro.app/Contents/Resources/TokenProBridge.js"
cp Resources/OpenCodex.command Resources/OpenClaude.command "$DEST/TokenPro.app/Contents/Resources/"
chmod 755 "$DEST/TokenPro.app/Contents/Resources/OpenCodex.command" "$DEST/TokenPro.app/Contents/Resources/OpenClaude.command"
cp Resources/CodexOfficial.icns Resources/ClaudeOfficial.icns "$DEST/TokenPro.app/Contents/Resources/"
cp Resources/WebCog.svg Resources/WebBook.svg "$DEST/TokenPro.app/Contents/Resources/"
cp Router.icns "$DEST/TokenPro.app/Contents/Resources/Router.icns"
codesign --force --sign - "$DEST/TokenPro.app"
"$DEST/TokenPro.app/Contents/MacOS/TokenPro" --self-test
