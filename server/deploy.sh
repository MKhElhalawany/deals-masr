#!/usr/bin/env bash
# Runs ON THE SERVER over SSH (called by GitHub Actions). Idempotent: installs Docker once, then (re)builds and
# restarts the worker container. The env file with the server key arrives via scp and stays mode 600.
set -euo pipefail
APP=~/deals-masr
if ! command -v docker >/dev/null; then
  sudo apt-get update -qq && sudo apt-get install -y -qq docker.io git >/dev/null
  sudo systemctl enable --now docker
fi
# Oracle's Ubuntu images block most ports by default; the worker only makes outbound connections, nothing to open.
if [ -d "$APP/.git" ]; then git -C "$APP" fetch -q --depth 1 origin main && git -C "$APP" reset -q --hard origin/main
else git clone -q --depth 1 https://github.com/MKhElhalawany/deals-masr.git "$APP"; fi
sudo docker build -q -t deals-worker "$APP/server/worker" >/dev/null
sudo docker rm -f deals-worker >/dev/null 2>&1 || true
sudo docker run -d --name deals-worker --restart unless-stopped --env-file ~/deals-worker.env \
  --shm-size=1g --memory=3g --log-opt max-size=20m --log-opt max-file=3 deals-worker >/dev/null
sleep 20
sudo docker ps --filter name=deals-worker --format '{{.Status}}'
sudo docker logs --tail 15 deals-worker 2>&1 | sed -E 's/(eyJ|sb_secret_)[A-Za-z0-9._-]+/***/g'
