#!/usr/bin/env bash
# Runs only on the disposable GitHub runner, after local-demo acceptance tests.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${RUNNER_TEMP:?This script is for GitHub Actions only}"

tested_image=$(docker compose images -q app)
docker tag "$tested_image" inventory-public-ci:tested
docker compose down -v
bash deploy/init-env.sh inventory.test
export APP_IMAGE=inventory-public-ci:tested
app=(docker compose --env-file deploy/.env.production -f deploy/compose.yml)
proxy=(docker compose --env-file deploy/.env.production -f deploy/proxy/compose.yml)
"${app[@]}" config --quiet
"${proxy[@]}" config --quiet
docker network create portfolio-edge
"${proxy[@]}" pull
"${proxy[@]}" run --rm --no-deps caddy caddy validate --config /etc/caddy/Caddyfile
"${app[@]}" up -d --pull never --wait --wait-timeout 300

# Use the production site routing, with a CI-only local certificate authority.
cat > "$RUNNER_TEMP/Caddyfile.inventory-ci" <<'EOF'
{
    local_certs
}
import /etc/caddy/sites/*.caddy
EOF
docker run -d --name inventory-proxy-ci --network portfolio-edge \
  -p 127.0.0.1:8443:443 -e DEMO_HOST=inventory.test \
  -v "$RUNNER_TEMP/Caddyfile.inventory-ci:/etc/caddy/Caddyfile:ro" \
  -v "$PWD/deploy/proxy/sites:/etc/caddy/sites:ro" caddy:2-alpine
for attempt in $(seq 1 30); do
  if docker cp inventory-proxy-ci:/data/caddy/pki/authorities/local/root.crt "$RUNNER_TEMP/inventory-ca.crt" 2>/dev/null; then
    break
  fi
  sleep 1
done
python3 scripts/public-smoke.py "$RUNNER_TEMP/inventory-ca.crt"
