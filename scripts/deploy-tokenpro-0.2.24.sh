#!/usr/bin/env bash
set -euo pipefail
umask 077

target="${1:?target required}"
digest="${2:?image digest required}"
[[ "$digest" =~ ^sha256:[a-f0-9]{64}$ ]]
old_image=tokenpro-local/zzz-backend:0.2.24-tp.1
old_revision=251c629801ece28c199f89ebec6d2ec3bd489b71
new_image=tokenpro-local/zzz-backend:0.2.24-tp.2
new_revision=e0e9ab6116d4349b9e4954306816e0fc8fd3dcf9
registry_image="ghcr.io/121882249/zzz-backend@$digest"

case "$target" in
  stage)
    container=pr7stage-app-1 project=pr7stage service=app directory_name=pr7stage
    postgres=pr7stage-postgres-1 redis=pr7stage-redis-1 port=18080
    ;;
  production)
    container=sub2api project=sub2api-deploy service=sub2api directory_name=sub2api-deploy
    postgres=sub2api-postgres redis=sub2api-redis port=8080
    ;;
  *) exit 2 ;;
esac

inspect() { docker inspect --format "$2" "$1"; }
running_version() { docker exec "$1" /app/sub2api -version 2>&1; }
db_count() {
  docker exec "$postgres" sh -ceu 'export PGPASSWORD=${POSTGRES_PASSWORD:-}
    exec psql -X -v ON_ERROR_STOP=1 -At -U "${POSTGRES_USER:-postgres}" \
      -d "${POSTGRES_DB:-${POSTGRES_USER:-postgres}}" -c "SELECT count(*) FROM public.schema_migrations"'
}
dependency_state() { docker inspect --format '{{.Id}} {{.State.StartedAt}}' "$postgres" "$redis"; }
healthy() { [[ "$(inspect "$1" '{{.State.Health.Status}}')" == healthy ]]; }
wait_healthy() {
  for _ in $(seq 1 36); do
    if healthy "$container"; then return 0; fi
    sleep 5
  done
  return 1
}

old_container="$(inspect "$container" '{{.Id}}')"
old_image_id="$(inspect "$container" '{{.Image}}')"
[[ "$(inspect "$container" '{{.Config.Image}}')" == "$old_image" ]]
[[ "$(docker image inspect --format '{{.Id}}' "$old_image")" == "$old_image_id" ]]
[[ "$(running_version "$container")" == *"Sub2API 0.2.24-tp.1 (commit: $old_revision,"* ]]
[[ "$(inspect "$container" '{{index .Config.Labels "com.docker.compose.project"}}')" == "$project" ]]
[[ "$(inspect "$container" '{{index .Config.Labels "com.docker.compose.service"}}')" == "$service" ]]
directory="$(inspect "$container" '{{index .Config.Labels "com.docker.compose.project.working_dir"}}')"
[[ "$directory" =~ ^/home/[[:alnum:]_][[:alnum:]_.-]*/$directory_name$ ]]
compose_file="$directory/docker-compose.yml"
[[ "$(inspect "$container" '{{index .Config.Labels "com.docker.compose.project.config_files"}}')" == "$compose_file" ]]
[[ -f "$compose_file" && ! -L "$compose_file" ]]
compose=(docker compose --project-directory "$directory" -p "$project" -f "$compose_file")
"${compose[@]}" config --images "$service" | grep -Fxq "$old_image"
healthy "$container"

docker pull "$registry_image"
new_image_id="$(docker image inspect --format '{{.Id}}' "$registry_image")"
candidate="$(docker run --rm --entrypoint /app/sub2api "$registry_image" -version 2>&1)"
[[ "$candidate" == *"Sub2API 0.2.24-tp.2 (commit: $new_revision,"* ]]
if existing="$(docker image inspect --format '{{.Id}}' "$new_image" 2>/dev/null)"; then
  [[ "$existing" == "$new_image_id" ]]
else
  docker tag "$registry_image" "$new_image"
fi

if [[ "$target" == production ]]; then
  healthy pr7stage-app-1
  [[ "$(inspect pr7stage-app-1 '{{.Image}}')" == "$new_image_id" ]]
  [[ "$(running_version pr7stage-app-1)" == *"Sub2API 0.2.24-tp.2 (commit: $new_revision,"* ]]
fi

db_before="$(db_count)"
[[ "$db_before" =~ ^[1-9][0-9]*$ ]]
deps_before="$(dependency_state)"
backup_root="$(dirname "$directory")/sub2api-backups"
mkdir -p "$backup_root"
backup="$(mktemp -d "$backup_root/$(date -u +%Y%m%dT%H%M%SZ)-$target-0.2.24-tp.2-XXXXXX")"
cp -p "$compose_file" "$backup/docker-compose.yml"
backup_files=(docker-compose.yml versions.txt)
if [[ -f "$directory/.env" ]]; then
  install -m 0600 "$directory/.env" "$backup/deployment.env"
  backup_files+=(deployment.env)
fi
printf '%s\n' "old_image=$old_image" "old_image_id=$old_image_id" "old_revision=$old_revision" \
  "new_image=$new_image" "new_manifest_digest=$digest" "new_revision=$new_revision" \
  "schema_migrations=$db_before" > "$backup/versions.txt"
if [[ "$target" == production ]]; then
  docker exec "$postgres" sh -ceu 'export PGPASSWORD=${POSTGRES_PASSWORD:-}
    exec pg_dump -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-${POSTGRES_USER:-postgres}}" -Fc' > "$backup/postgres.dump"
  test -s "$backup/postgres.dump"
  docker exec -i "$postgres" pg_restore --list < "$backup/postgres.dump" > /dev/null
  backup_files+=(postgres.dump)
fi
(cd "$backup" && sha256sum "${backup_files[@]}" > SHA256SUMS && sha256sum -c SHA256SUMS)

[[ "$(inspect "$container" '{{.Id}}')" == "$old_container" ]]
cmp -s "$compose_file" "$backup/docker-compose.yml"

rollback_on_error() {
  status=$?
  if (( status == 0 )); then return; fi
  trap - EXIT
  if cmp -s "$compose_file" "$backup/docker-compose.yml"; then exit "$status"; fi
  echo "Deployment failed. Restoring $old_image from $backup"
  if cp -p "$backup/docker-compose.yml" "$compose_file" &&
    "${compose[@]}" up -d --no-deps --no-build --pull never "$service" &&
    wait_healthy && [[ "$(inspect "$container" '{{.Image}}')" == "$old_image_id" ]]; then
    echo 'Rollback verified healthy.'
  else
    echo 'Rollback could not be verified.' >&2
  fi
  exit "$status"
}
trap rollback_on_error EXIT
trap 'exit 143' TERM
trap 'exit 129' HUP

python3 - "$compose_file" "$old_image" "$new_image" <<'PY'
import os, pathlib, re, sys, tempfile

path = pathlib.Path(sys.argv[1])
old, new = (value.encode() for value in sys.argv[2:])
pattern = rb'(?m)^([ \t]*image:[ \t]*)' + re.escape(old) + rb'([ \t]*)(?=\r?$)'
updated, count = re.subn(pattern, lambda match: match[1] + new + match[2], path.read_bytes())
if count != 1:
    raise SystemExit('Expected exactly one old image assignment.')
stat = path.stat()
with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as handle:
    os.fchown(handle.fileno(), stat.st_uid, stat.st_gid)
    os.fchmod(handle.fileno(), stat.st_mode & 0o777)
    handle.write(updated)
os.replace(handle.name, path)
PY
"${compose[@]}" config --quiet
"${compose[@]}" config --images "$service" | grep -Fxq "$new_image"
"${compose[@]}" up -d --no-deps --no-build --pull never "$service"
wait_healthy
[[ "$(inspect "$container" '{{.Image}}')" == "$new_image_id" ]]
actual="$(running_version "$container")"
[[ "$actual" == *"Sub2API 0.2.24-tp.2 (commit: $new_revision,"* ]]
health="$(curl --noproxy '*' --fail --silent --show-error --max-time 8 "http://127.0.0.1:$port/health")"
python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "ok"' <<< "$health"
[[ "$(curl --noproxy '*' --silent --show-error --max-time 8 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/v1/models")" == 401 ]]
[[ "$(db_count)" == "$db_before" && "$(dependency_state)" == "$deps_before" ]]

python3 - "$port" <<'PY'
import re
import sys
from urllib.request import urlopen

origin = 'http://127.0.0.1:' + sys.argv[1]

def fetch(path):
    with urlopen(origin + path, timeout=10) as response:
        return response.read().decode()

entry = re.search(r'<script\b[^>]+src="(/assets/[\w.-]+\.js)"', fetch('/model-plaza'))
if entry is None:
    raise SystemExit('Model Plaza page did not serve its JavaScript entry point.')
chunk = re.search(r'ModelPlazaView-[\w-]+\.js', fetch(entry[1]))
if chunk is None:
    raise SystemExit('Model Plaza JavaScript was not referenced by the running site.')
pricing_page = fetch('/assets/' + chunk[0])
if 'modelPlaza.table.cacheWrite' not in pricing_page or 'modelPlaza.table.cacheRead' in pricing_page:
    raise SystemExit('Model Plaza assets do not contain the expected cache price display.')
print('Model Plaza assets verified: cache write display present, cache read display absent.')
PY

trap - EXIT TERM HUP
printf '%s\n' "$actual"
printf 'Deployment verified | target=%s | digest=%s | schema_migrations=%s | backup=%s\n' "$target" "$digest" "$db_before" "$backup"
