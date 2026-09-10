#!/usr/bin/env bash
set -euo pipefail

version="${1:?version is required}"
source_dir="${2:?source directory is required}"
target_root="${3:-/var/www/tokenpro-downloads}"

if [[ ! "$version" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Invalid release version: $version" >&2
  exit 1
fi

suffixes=(
  macOS-arm64.dmg
  macOS-x64.dmg
  Windows-x64.exe
  Linux-x64.deb
)
update_file="$source_dir/TokenPro-${version}-update.jar"

for suffix in "${suffixes[@]}"; do
  file="$source_dir/TokenPro-${version}-${suffix}"
  if [[ ! -s "$file" ]]; then
    echo "Missing release artifact: $file" >&2
    exit 1
  fi
done
release_dir="$target_root/$version"
latest_dir="$target_root/latest"
install -d -m 0755 "$release_dir" "$latest_dir"

for suffix in "${suffixes[@]}"; do
  install -m 0644 "$source_dir/TokenPro-${version}-${suffix}" "$release_dir/TokenPro-${version}-${suffix}"
done
if [[ -s "$update_file" ]]; then
  install -m 0644 "$update_file" "$release_dir/TokenPro-${version}-update.jar"
fi

(
  cd "$release_dir"
  sha256sum TokenPro-* > SHA256SUMS.new
  mv SHA256SUMS.new SHA256SUMS
)

for suffix in "${suffixes[@]}"; do
  ln -sfn "../$version/TokenPro-${version}-${suffix}" "$latest_dir/TokenPro-${suffix}"
done
ln -sfn "../$version/SHA256SUMS" "$latest_dir/SHA256SUMS"
incremental_json=""
if [[ -s "$release_dir/TokenPro-${version}-update.jar" ]]; then
  ln -sfn "../$version/TokenPro-${version}-update.jar" "$latest_dir/TokenPro-update.jar"
  update_sha=$(sha256sum "$release_dir/TokenPro-${version}-update.jar" | awk '{print $1}')
  incremental_json=",\"incremental\":{\"url\":\"https://tokenpro.work/downloads/latest/TokenPro-update.jar\",\"sha256\":\"$update_sha\"}"
else
  rm -f "$latest_dir/TokenPro-update.jar"
fi

mac_arm_sha=$(sha256sum "$release_dir/TokenPro-${version}-macOS-arm64.dmg" | awk '{print $1}')
mac_x64_sha=$(sha256sum "$release_dir/TokenPro-${version}-macOS-x64.dmg" | awk '{print $1}')
windows_x64_sha=$(sha256sum "$release_dir/TokenPro-${version}-Windows-x64.exe" | awk '{print $1}')
linux_x64_sha=$(sha256sum "$release_dir/TokenPro-${version}-Linux-x64.deb" | awk '{print $1}')
cat > "$latest_dir/release.json.new" <<EOF
{"tag_name":"$version","html_url":"https://tokenpro.work/#download-dock-title"${incremental_json},"downloads":{"macos-arm64":{"url":"https://tokenpro.work/downloads/latest/TokenPro-macOS-arm64.dmg","sha256":"$mac_arm_sha"},"macos-x64":{"url":"https://tokenpro.work/downloads/latest/TokenPro-macOS-x64.dmg","sha256":"$mac_x64_sha"},"windows-x64":{"url":"https://tokenpro.work/downloads/latest/TokenPro-Windows-x64.exe","sha256":"$windows_x64_sha"},"linux-x64":{"url":"https://tokenpro.work/downloads/latest/TokenPro-Linux-x64.deb","sha256":"$linux_x64_sha"}}}
EOF
mv "$latest_dir/release.json.new" "$latest_dir/release.json"

echo "Published TokenPro $version to $release_dir"
