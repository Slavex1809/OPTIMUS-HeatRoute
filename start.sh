#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ "${1:-}" == "--local" ]]; then
  shift
  if [[ -n "${JAVA_HOME:-}" ]]; then java_bin="$JAVA_HOME/bin/java"; else java_bin=java; fi
  exec "$java_bin" -Xms128m -Xmx2g -jar release/optimus-heatroute.jar --spring.profiles.active=local "$@"
fi
exec ./deploy.sh "$@"
