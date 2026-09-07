#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
./build.sh build-latest
mkdir -p ../../outputs/latest ../../work/dmg-stage-latest ../../outputs/client-updates
ditto 'build-latest/TokenPro.app' '../../outputs/latest/TokenPro.app'
ditto 'build-latest/TokenPro.app' '../../work/dmg-stage-latest/TokenPro.app'
ln -sfn /Applications ../../work/dmg-stage-latest/Applications
hdiutil create -volname 'TokenPro' -srcfolder ../../work/dmg-stage-latest -ov -format UDZO ../../outputs/client-updates/TokenPro-latest-macOS-universal.dmg
python3 - <<'PY'
import json, pathlib, plistlib, hashlib
root = pathlib.Path('../../outputs/client-updates')
dmg = root / 'TokenPro-latest-macOS-universal.dmg'
with open('build-latest/TokenPro.app/Contents/Info.plist', 'rb') as f:
    version = plistlib.load(f)['CFBundleShortVersionString']
manifest = {'version': version, 'downloadURL': 'https://tokenpro.work/client-updates/' + dmg.name,
            'notes': pathlib.Path('release-notes.txt').read_text(), 'sha256': hashlib.sha256(dmg.read_bytes()).hexdigest()}
(root / 'macos.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
(root / 'macos-intel.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
PY
codesign --verify --deep --strict '../../outputs/latest/TokenPro.app'
hdiutil verify ../../outputs/client-updates/TokenPro-latest-macOS-universal.dmg
