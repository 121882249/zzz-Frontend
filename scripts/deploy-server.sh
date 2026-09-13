#!/usr/bin/env bash
# Run remotely via sudo bash -s, never print compose/environment credentials.
set -euo pipefail
version="${1:?version required}"
expected="${2:?expected current version required}"
[[ "$version" =~ ^TokenPro-R[0-9]+-v[0-9]+\.[0-9]+\.[0-9]+$ ]]
[[ "$expected" =~ ^TokenPro-R[0-9]+-v[0-9]+\.[0-9]+\.[0-9]+$ ]]
[[ "$version" != "$expected" ]]
image="ghcr.io/121882249/zzz-backend:$version"
old_image="ghcr.io/121882249/zzz-backend:$expected"
current="$(docker inspect sub2api --format '{{.Config.Image}}')"
[[ "$current" == "$old_image" ]] || { echo 'Unexpected running image; refusing deployment.'; exit 1; }
service="$(docker inspect sub2api --format '{{index .Config.Labels "com.docker.compose.service"}}')"
project="$(docker inspect sub2api --format '{{index .Config.Labels "com.docker.compose.project"}}')"
directory="$(docker inspect sub2api --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}')"
files="$(docker inspect sub2api --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}')"
[[ "$service" == sub2api && "$project" == sub2api-deploy ]]
[[ "$directory" =~ ^/home/[^/]+/sub2api-deploy$ ]]
[[ "$files" == "$directory/docker-compose.yml" && -f "$files" ]]
cd "$directory"
# Require exactly one literal image assignment; never rewrite other services.
pattern="^[[:space:]]*image:[[:space:]]*ghcr[.]io/121882249/zzz-backend:${expected//./[.]}[[:space:]]*$"
[[ "$(grep -Ec "$pattern" docker-compose.yml)" == 1 ]]
docker pull "$image"
new_id="$(docker image inspect "$image" --format '{{.Id}}')"
backup="docker-compose.yml.pre-$version-$(date -u +%Y%m%dT%H%M%SZ)"
cp -p docker-compose.yml "$backup"
rollback() {
  echo "Deployment failed; restoring $expected using $backup"
  cp -p "$backup" docker-compose.yml
  docker compose -p "$project" -f docker-compose.yml up -d --no-deps sub2api
}
trap rollback ERR
sed -i -E "s|$pattern|    image: $image|" docker-compose.yml
docker compose -p "$project" -f docker-compose.yml config --quiet
docker compose -p "$project" -f docker-compose.yml up -d --no-deps sub2api
healthy=false
for attempt in $(seq 1 36); do
  state="$(docker inspect sub2api --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')"
  if [[ "$state" == healthy ]]; then healthy=true; break; fi
  sleep 5
done
[[ "$healthy" == true ]]
[[ "$(docker inspect sub2api --format '{{.Image}}')" == "$new_id" ]]
trap - ERR
echo "Deployed $version; healthy; image=$new_id; backup=$directory/$backup"
docker ps --format '{{.Names}} | {{.Image}} | {{.Status}}'
