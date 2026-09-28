#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
. /etc/os-release
[[ "$ID" == ubuntu && "$VERSION_ID" == 22.04 && "$(uname -m)" == x86_64 ]] || { echo 'Required: Ubuntu Server 22.04 x86_64'; exit 1; }
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg openssl python3 unzip
if command -v docker >/dev/null; then
  echo 'Preserving existing Docker installation.'
else
  sudo install -m 0755 -d /etc/apt/keyrings
  key=$(mktemp)
  trap 'rm -f "$key"' EXIT
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o "$key"
  sudo install -m 0644 "$key" /etc/apt/keyrings/docker.asc
  echo 'deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu jammy stable' | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
  sudo apt-get update
  engine='5:24.0.9-1~ubuntu.22.04~jammy'
  sudo apt-get install -y --no-install-recommends "docker-ce=$engine" "docker-ce-cli=$engine" containerd.io=1.6.33-1 'docker-buildx-plugin=0.12.1-1~ubuntu.22.04~jammy'
  sudo apt-mark hold docker-ce docker-ce-cli containerd.io docker-buildx-plugin
fi
sudo systemctl enable --now docker
bash scripts/install-compose-1.29.2.sh
echo 'Next: sudo env OPTIMUS_CONTEST=1 bash deploy.sh'
