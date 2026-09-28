#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
bash scripts/contest-preflight.sh
export OPTIMUS_CONTEST=1
exec bash deploy.sh "${@}"
