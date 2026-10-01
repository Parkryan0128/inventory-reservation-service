#!/usr/bin/env bash
# Exercise cleanup against real Docker images on the disposable CI runner.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${RUNNER_TEMP:?This script is for GitHub Actions only}"
repository=ghcr.io/parkryan0128/inventory-reservation-service
current="$repository:sha-$(printf '%040d' 1)"
previous="$repository:sha-$(printf '%040d' 2)"
unused="$repository:sha-$(printf '%040d' 3)"
in_use="$repository:sha-$(printf '%040d' 4)"
alias="$repository:sha-$(printf '%040d' 5)"
other=ghcr.io/parkryan0128/opti-route:sha-$(printf '%040d' 6)
seed=
container=
cleanup() {
  if [[ -n "$container" ]]; then docker rm -f "$container" >/dev/null; fi
  if [[ -n "$seed" ]]; then docker rm -f "$seed" >/dev/null; fi
  docker image rm "$current" "$previous" "$unused" "$in_use" "$alias" "$other" >/dev/null 2>&1 || true
  rm -f deploy/.deployed-image deploy/.previous-image
}
trap cleanup EXIT
seed=$(docker create "$(docker compose images -q app)")
for image in "$current" "$previous" "$unused" "$in_use" "$other"; do
  docker commit --change "LABEL cleanup-case=$image" "$seed" "$image" >/dev/null
done
docker tag "$current" "$alias"
# A stopped container still protects its image from removal.
container=$(docker create "$in_use")
printf '%s\n' "$current" > deploy/.deployed-image
printf '%s\n' "$previous" > deploy/.previous-image
bash deploy/prune-images.sh
for image in "$current" "$previous" "$in_use" "$alias" "$other"; do
  docker image inspect "$image" >/dev/null
done
if docker image inspect "$unused" >/dev/null 2>&1; then
  echo 'Unused inventory image was not removed.' >&2
  exit 1
fi
# Losing a retention reference must stop cleanup before deleting anything.
docker tag "$other" "$unused"
printf '%s\n' "$repository:sha-$(printf '%040d' 7)" > deploy/.previous-image
if bash deploy/prune-images.sh; then
  echo 'Cleanup accepted a missing retention image.' >&2
  exit 1
fi
docker image inspect "$unused" >/dev/null
echo 'Image cleanup passed: current, previous, aliases, containers and other repositories retained.'
