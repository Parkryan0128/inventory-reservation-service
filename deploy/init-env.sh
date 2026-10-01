#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

demo_host=${1:?Usage: bash deploy/init-env.sh inventory.example.com}
if [[ ! "$demo_host" =~ ^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?$ ]]; then
  echo 'Use a hostname without https://, a port, or a path.' >&2
  exit 1
fi
if [[ -e deploy/.env.production ]]; then
  echo 'deploy/.env.production already exists; keeping its credentials.'
  exit 0
fi
command -v openssl >/dev/null
revision=$(git rev-parse HEAD)
umask 077
env_temp=$(mktemp deploy/.env.production.XXXXXX)
trap 'rm -f "$env_temp"' EXIT
{
  printf 'DEMO_HOST=%s\n' "$demo_host"
  printf 'APP_IMAGE=ghcr.io/parkryan0128/inventory-reservation-service:sha-%s\n' "$revision"
  printf 'DATABASE_PASSWORD=%s\n' "$(openssl rand -hex 24)"
} > "$env_temp"
# Linking fails if another setup process created the destination in the meantime.
ln "$env_temp" deploy/.env.production
echo 'Created deploy/.env.production with private, randomly generated credentials.'
