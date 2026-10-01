# Public VPS deployment

The public demo uses a separate `public-demo` profile. It supports the five scenarios and shared Manual inventory, with anonymous browser sessions, CSRF protection, and secure session cookies. The normal customer/admin APIs and `/index.html` are denied even to authenticated accounts. Demo API requests must use `DEMO_HOST`.

The local `compose.yml` and `demo` profile remain local development tools. Use the files under `deploy/` on the VPS.

## First deployment

Prerequisites: Linux amd64 VPS, Docker Engine with Compose, Git, OpenSSL, and curl. Point the demo hostname's A record to the VPS. TCP ports 80 and 443 must be reachable for HTTPS; SSH must remain reachable. Do not publish the PostgreSQL, Redis, Kafka, or application ports. This setup uses one application instance.

1. Wait for the main branch's **Acceptance** workflow to finish successfully. The workflow tests the normal API/demo, then the production Compose stack over HTTPS using a private CI certificate authority. It publishes the exact tested image as `ghcr.io/parkryan0128/inventory-reservation-service:sha-<full-commit>`.
2. Check the GitHub container package visibility. If it is private, use **Package settings → Change visibility → Public**. Public images can be pulled anonymously; the VPS does not need a GitHub token. The package should remain associated with this public repository.
3. In the VPS checkout, run:

   ```bash
   git pull --ff-only origin main
   bash deploy/init-env.sh inventory.ryanparkdev.com
   bash deploy/up.sh
   ```

The setup script generates four random credentials in `deploy/.env.production` with owner-only permissions. It leaves an existing file unchanged. Keep this file on the server; do not commit or paste it into chat. No passwords are printed.

The deployment script pulls images, validates Compose and Caddy, starts the database/Redis/Kafka/application, waits for health checks, starts the shared HTTPS proxy, and checks the public HTTPS health endpoint with normal certificate verification. It uses `sudo docker` if the current user cannot access Docker. It never builds an image or deletes database volumes.

Open `https://inventory.ryanparkdev.com/`. If image pulling says `denied`, check package visibility. If the final HTTPS check fails, inspect DNS, port reachability, and the proxy logs; do not rerun environment setup or delete database volumes.

## Updates and rollback

After a new main commit passes Acceptance:

```bash
git pull --ff-only origin main
bash deploy/up.sh
```

By default, the script deploys the checked-out commit's image. It records successful releases in `deploy/.deployed-image`, and the preceding release in `deploy/.previous-image`. These files and the secrets are ignored by Git. An unhealthy startup or failed public HTTPS check returns a failure; it does not report a successful deployment or automatically roll back.

To roll back to the previous successful image:

```bash
bash deploy/up.sh "$(cat deploy/.previous-image)"
```

Only roll back across database migrations if the old application supports the current schema. This command changes the application image, not database contents. A single application instance has a short interruption during updates; browser session ownership and the in-memory activity feed reset when it restarts. Saved stock, orders, and events persist.

## Operations

```bash
sudo docker compose --env-file deploy/.env.production -f deploy/compose.yml ps
sudo docker compose --env-file deploy/.env.production -f deploy/compose.yml logs --tail 100 app
sudo docker compose --env-file deploy/.env.production -f deploy/proxy/compose.yml logs --tail 100 caddy
sudo docker stats --no-stream
free -h
df -h /
```

Container memory limits total 2.5 GiB including Caddy: application 768 MiB, PostgreSQL 512 MiB, Redis 128 MiB, Kafka 1 GiB, and proxy 128 MiB. These are limits, not measured usage. Check actual memory before adding another project to a 4 GB VPS. Services restart unless explicitly stopped; the Docker service must also be enabled at boot. An `unhealthy` container is reported by health checks, but Docker's restart policy restarts exited processes, not merely unhealthy ones.

Container stdout logs rotate at 10 MB × 3 files per container. The Manual feed keeps 200 entries in memory. Kafka event logs retain approximately 24 hours or 256 MiB per partition, with 16 MiB/one-hour segment rollover; these settings do not cap the whole Kafka data volume. PostgreSQL business records are not automatically deleted, and stock is not automatically reset. Named volumes survive container replacements but are not backups; preserve the database and credentials separately if its demo history matters.

Public scenarios accept one start every ten seconds across the application, in addition to the existing one-running-scenario limit. Manual actions retain the 500 ms per-session limit.

## Adding another project

Only the shared `portfolio-proxy` project owns ports 80/443. Attach the next project's web container to the external `portfolio-edge` network using a unique alias, for example `optiroute-web`. Keep its database on its own private network unless database sharing is deliberately configured.

Add a server-local file `deploy/proxy/sites/optiroute.local.caddy`:

```caddyfile
optiroute.ryanparkdev.com {
    reverse_proxy optiroute-web:8000
}
```

Replace port 8000 with that project's actual internal web port. `*.local.caddy` files are ignored by Git, so pulling this repository will preserve local site configuration. Check the new application's host/CSRF/proxy settings before routing visitors to it.

Validate and reload the shared proxy after the new application is healthy:

```bash
sudo docker compose --env-file deploy/.env.production -f deploy/proxy/compose.yml exec -T caddy caddy validate --config /etc/caddy/Caddyfile
sudo docker compose --env-file deploy/.env.production -f deploy/proxy/compose.yml exec -T caddy caddy reload --config /etc/caddy/Caddyfile
```

Do not start another proxy on the same host ports. Retain the existing project's DNS target until its new deployment is ready.

## Scope of automation

Main pushes run tests and publish images. The **Deploy** workflow then updates the VPS when repository variable `DEPLOY_ENABLED=true`. Pull requests do not deploy. A manual Deploy run also requires a successful main Acceptance run for that exact commit; it reuses the published image without rebuilding.

Install the two repository-specific public deployment keys once, as `ubuntu` on the VPS:

```bash
cd ~/apps/inventory-reservation-service
git pull --ff-only origin main
bash deploy/install-cd-keys.sh
```

The installer adds keys without removing existing SSH access and adds `ubuntu` to the Docker group so fresh deployment SSH sessions need no interactive sudo password. Each key is restricted to deploying one repository; it cannot open an interactive shell or forward ports. The corresponding private keys are GitHub Secrets, never repository files.

GitHub Secrets: `DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS` (the verified VPS host key). Repository variables: `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_ENABLED`. Activate each repository only after its initial application setup and key installation have succeeded.

`deploy/receive.sh` accepts only a full commit SHA, requires a clean main checkout, fetches the remote and skips superseded releases. It only fast-forwards; it will not reset local work. A shared VM file lock serializes both projects' deployments and Caddy reloads. Failed startup/HTTPS checks fail the workflow; inspect the logs and use the documented compatible-image rollback if needed.
