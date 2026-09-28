#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
output=evidence/acceptance-latest
mkdir -p "$output"
python3 scripts/smoke.py --url "${OPTIMUS_URL:-http://127.0.0.1:8080}" --file data/corrected.geojson --out "$output/result.geojson"
python3 scripts/verify_result.py data/corrected.geojson "$output/result.geojson"
python3 scripts/visualize_result.py data/corrected.geojson "$output/result.geojson" "$output/result.report.json" "$output/result.html"
python3 - <<'PY'
import json
r=json.load(open('evidence/acceptance-latest/result.report.json'))['report']
assert r['entryPolicy']=='cost',r['entryPolicy']
assert r['nearestBoundaryRequired'] is False
assert r['connected']==r['targets']==17, f"Full coverage gate failed: {r['connected']}/{r['targets']}; see generated report and map"
print('PASS: all 17 reference targets connected under the user-selected admissible-entry policy')
PY
printf 'Acceptance complete. Results: %s\n' "$output"
