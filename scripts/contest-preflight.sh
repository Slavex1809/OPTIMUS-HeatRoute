#!/usr/bin/env bash
set -euo pipefail
. /etc/os-release
[[ "$ID" == ubuntu && "$VERSION_ID" == 22.04 ]] || { echo 'Required: Ubuntu Server 22.04 LTS'; exit 1; }
[[ "$(uname -m)" == x86_64 ]] || { echo 'This package targets x86_64'; exit 1; }
[[ "$(docker-compose version --short)" == 1.29.2 ]] || { echo 'Required: docker-compose 1.29.2'; exit 1; }
docker info >/dev/null
engine_major=$(docker version --format '{{.Server.Version}}' | cut -d. -f1)
[[ "$engine_major" =~ ^[0-9]+$ && "$engine_major" -ge 20 && "$engine_major" -lt 26 ]] || { echo 'Compose 1.29.2 target: Engine 20-25. Installer uses 24.0.9. See docs/DEPLOYMENT.md.'; exit 1; }
printf 'PASS: Ubuntu 22.04, docker-compose 1.29.2, Docker Engine reachable\n'
docker version
free -h
