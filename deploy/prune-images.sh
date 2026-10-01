#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker_command=("$@")
if [[ ${#docker_command[@]} -eq 0 ]]; then docker_command=(docker); fi
repository=ghcr.io/parkryan0128/inventory-reservation-service

# Resolve IDs so digest references and extra tags of retained images are also protected.
current_id=$("${docker_command[@]}" image inspect --format '{{.Id}}' "$(cat deploy/.deployed-image)")
previous_id=
if [[ -f deploy/.previous-image ]]; then
  previous_id=$("${docker_command[@]}" image inspect --format '{{.Id}}' "$(cat deploy/.previous-image)")
fi
images=$("${docker_command[@]}" image ls --filter "reference=$repository:sha-*" --format '{{.Repository}}:{{.Tag}}')
while IFS= read -r image; do
  [[ "$image" == "$repository:"* ]] || continue
  [[ "${image##*:}" =~ ^sha-[a-f0-9]{40}$ ]] || continue
  image_id=$("${docker_command[@]}" image inspect --format '{{.Id}}' "$image")
  [[ "$image_id" != "$current_id" && "$image_id" != "$previous_id" ]] || continue
  containers=$("${docker_command[@]}" ps -aq --filter "ancestor=$image_id")
  [[ -z "$containers" ]] || continue
  "${docker_command[@]}" image rm "$image"
done <<< "$images"
