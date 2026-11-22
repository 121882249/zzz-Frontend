#!/usr/bin/env bash
set -euo pipefail

old_image=tokenpro-local/zzz-backend:0.2.24-tp.2
for container in sub2api pr7stage-app-1; do
  docker inspect --format '{{.Name}} | {{.Config.Image}} | project={{index .Config.Labels "com.docker.compose.project"}} | service={{index .Config.Labels "com.docker.compose.service"}} | directory={{index .Config.Labels "com.docker.compose.project.working_dir"}} | files={{index .Config.Labels "com.docker.compose.project.config_files"}} | env_file={{index .Config.Labels "com.docker.compose.project.environment_file"}} | {{.State.Health.Status}}' "$container"
  docker port "$container"
  docker exec "$container" /app/sub2api -version
  IFS=, read -ra compose_files <<< "$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}' "$container")"
  for compose_file in "${compose_files[@]}"; do
    awk -v tag="$old_image" '$1=="image:" && $2==tag {count++} END {printf "Existing literal image assignments in %s: %d\n", FILENAME, count}' "$compose_file"
  done
done
docker version --format 'server={{.Server.Version}} platform={{.Server.Os}}/{{.Server.Arch}}'
docker compose version --short
df -h /home /var/lib/docker
