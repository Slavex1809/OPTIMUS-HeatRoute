#!/usr/bin/env bash
set -euo pipefail
. /etc/os-release
[[ "$ID" == ubuntu && "$VERSION_ID" == 22.04 && "$(uname -m)" == x86_64 ]] || { echo 'Required: Ubuntu 22.04 x86_64'; exit 1; }
if command -v docker-compose >/dev/null; then
  [[ "$(docker-compose version --short)" == 1.29.2 ]] && exit 0
  echo 'Another docker-compose exists. Preserve it and resolve PATH before installation.' >&2
  exit 1
fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cd "$tmp"
base=https://github.com/docker/compose/releases/download/1.29.2
curl -fL "$base/docker-compose-Linux-x86_64" -o docker-compose-Linux-x86_64
curl -fL "$base/docker-compose-Linux-x86_64.sha256" -o checksum
expected=$(awk '{print $1}' checksum)
[[ "$expected" =~ ^[a-fA-F0-9]{64}$ ]]
printf '%s  docker-compose-Linux-x86_64\n' "$expected" | sha256sum -c -
sudo install -m 0755 docker-compose-Linux-x86_64 /usr/local/bin/docker-compose
docker-compose version
