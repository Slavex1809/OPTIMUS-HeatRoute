#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mvn -B -f backend/pom.xml test
if command -v node >/dev/null; then node scripts/ui-test.cjs; fi
if [[ "${1:-}" == "--http" ]]; then
  python3 scripts/smoke.py
  python3 scripts/verify_result.py data/corrected.geojson evidence/http-result.geojson
  python3 scripts/load_50.py
fi
