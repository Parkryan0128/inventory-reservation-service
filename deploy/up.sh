#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f deploy/.env.production ]]; then
  echo 'First run: bash deploy/init-env.sh inventory.example.com' >&2
  exit 1
fi
export APP_IMAGE=${1:-ghcr.io/parkryan0128/inventory-reservation-service:sha-$(git rev-parse HEAD)}
if [[ "${PORTFOLIO_DEPLOY_LOCKED:-0}" != 1 ]]; then
  mkdir -p "$HOME/.cache"
  exec 9>"$HOME/.cache/portfolio-deploy.lock"
  flock -w 600 9
fi
if [[ ! "$APP_IMAGE" =~ ^ghcr.io/parkryan0128/inventory-reservation-service(:sha-[a-f0-9]{40}|@sha256:[a-f0-9]{64})$ ]]; then
  echo 'Use a published commit image or image digest from this repository.' >&2
  exit 1
fi
docker_command=(docker)
if ! docker info >/dev/null 2>&1; then
  docker_command=(sudo env "APP_IMAGE=$APP_IMAGE" docker)
fi
app=("${docker_command[@]}" compose --env-file deploy/.env.production -f deploy/compose.yml)
proxy=("${docker_command[@]}" compose --env-file deploy/.env.production -f deploy/proxy/compose.yml)

"${app[@]}" config --quiet
"${proxy[@]}" config --quiet
"${app[@]}" pull
"${proxy[@]}" pull
"${docker_command[@]}" network inspect portfolio-edge >/dev/null 2>&1 || "${docker_command[@]}" network create portfolio-edge
"${proxy[@]}" run --rm --no-deps caddy caddy validate --config /etc/caddy/Caddyfile
"${app[@]}" up -d --remove-orphans --wait --wait-timeout 300
"${proxy[@]}" up -d --wait --wait-timeout 60
"${proxy[@]}" exec -T caddy caddy reload --config /etc/caddy/Caddyfile

demo_host=$(sed -n 's/^DEMO_HOST=//p' deploy/.env.production)
curl --fail --silent --show-error --retry 12 --retry-delay 5 --retry-all-errors \
  --connect-timeout 5 --max-time 10 "https://${demo_host}/actuator/health"

umask 077
if [[ -f deploy/.deployed-image ]] && [[ "$(cat deploy/.deployed-image)" != "$APP_IMAGE" ]]; then
  cp deploy/.deployed-image deploy/.previous-image
fi
printf '%s\n' "$APP_IMAGE" > deploy/.deployed-image
env_temp=$(mktemp deploy/.env.production.XXXXXX)
trap 'rm -f "$env_temp"' EXIT
awk -v image="$APP_IMAGE" '/^APP_IMAGE=/ {$0="APP_IMAGE=" image} {print}' deploy/.env.production > "$env_temp"
mv "$env_temp" deploy/.env.production
if ! bash deploy/prune-images.sh "${docker_command[@]}"; then
  echo 'Deployment succeeded, but old image cleanup did not finish.' >&2
fi
printf '\nDeployed %s\nOpen https://%s/\n' "$APP_IMAGE" "$demo_host"
