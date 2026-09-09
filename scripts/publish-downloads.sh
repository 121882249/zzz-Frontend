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

(
  cd "$release_dir"
  sha256sum TokenPro-* > SHA256SUMS.new
  mv SHA256SUMS.new SHA256SUMS
)

for suffix in "${suffixes[@]}"; do
  ln -sfn "../$version/TokenPro-${version}-${suffix}" "$latest_dir/TokenPro-${suffix}"
done
ln -sfn "../$version/SHA256SUMS" "$latest_dir/SHA256SUMS"

echo "Published TokenPro $version to $release_dir"
