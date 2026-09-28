#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ "${OPTIMUS_CONTEST:-0}" == 1 ]]; then
  bash scripts/contest-preflight.sh
  compose=(docker-compose)
elif docker compose version >/dev/null 2>&1; then
  compose=(docker compose)
elif command -v docker-compose >/dev/null; then
  compose=(docker-compose)
else
  echo 'Docker Compose is required. For Java-only launch: ./start.sh --local' >&2; exit 1
fi
docker info >/dev/null
if [[ ! -f .env ]]; then
  umask 077
  if command -v openssl >/dev/null; then
    password=$(openssl rand -hex 24)
  elif command -v python3 >/dev/null; then
    password=$(python3 -c 'import secrets; print(secrets.token_hex(24))')
  else
    echo 'Create .env from .env.example with a DB_PASSWORD first.' >&2; exit 1
  fi
  printf 'DB_PASSWORD=%s\nBIND_ADDRESS=127.0.0.1\nHTTP_PORT=8080\n' "$password" > .env
fi
case "${1:-up}" in
  up)
    "${compose[@]}" config --quiet
    "${compose[@]}" up --build -d
    for attempt in {1..45}; do
      if "${compose[@]}" exec -T app /app/healthcheck.sh >/dev/null 2>&1; then
        echo 'OPTIMUS ready. Default URL: http://127.0.0.1:8080'; exit 0
      fi
      sleep 2
    done
    "${compose[@]}" ps
    echo 'Health check timed out. Run ./deploy.sh logs' >&2; exit 1;;
  prebuilt)
    "${compose[@]}" -f docker-compose.yml -f compose.prebuilt.yml config --quiet
    "${compose[@]}" -f docker-compose.yml -f compose.prebuilt.yml up --build -d;;
  status) "${compose[@]}" ps;;
  logs) "${compose[@]}" logs --tail=150 -f;;
  stop) "${compose[@]}" stop;;
  *) echo 'Usage: ./deploy.sh [up|prebuilt|status|logs|stop]' >&2; exit 2;;
esac
