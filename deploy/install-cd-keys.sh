#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ "$(id -un)" == ubuntu ]] || { echo 'Run this as ubuntu, without sudo bash.' >&2; exit 1; }
receiver="$PWD/deploy/receive.sh"
[[ "$PWD" == "$HOME/apps/inventory-reservation-service" ]] || { echo 'Use the checkout in ~/apps/inventory-reservation-service.' >&2; exit 1; }
mkdir -p "$HOME/.ssh"
chmod 700 "$HOME/.ssh"
touch "$HOME/.ssh/authorized_keys"
chmod 600 "$HOME/.ssh/authorized_keys"
for repo in inventory-reservation-service opti-route; do
  read -r key_type key_data _ < "deploy/cd/$repo.pub"
  [[ "$key_type" == ssh-ed25519 && "$key_data" =~ ^[A-Za-z0-9+/=]+$ ]] || exit 1
  entry="restrict,command=\"/bin/bash $receiver $repo\" $key_type $key_data github-cd-$repo"
  if ! grep -Fqx "$entry" "$HOME/.ssh/authorized_keys"; then
    printf '%s\n' "$entry" >> "$HOME/.ssh/authorized_keys"
  fi
done
# New SSH sessions can use Docker without an interactive sudo password.
sudo usermod -aG docker ubuntu
echo 'Installed two repository-specific deployment keys. New SSH sessions can deploy.'
