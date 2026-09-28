#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p evidence/contest
exec > >(tee evidence/contest/acceptance.log) 2>&1
date -u +%FT%TZ
bash scripts/contest-preflight.sh
docker-compose config --quiet
docker-compose exec -T db psql -v ON_ERROR_STOP=1 -U heatroute -d heatroute -c 'SELECT PostGIS_Full_Version();'
docker-compose exec -T app java -version
bash scripts/acceptance.sh
python3 scripts/load_50.py --url "${OPTIMUS_URL:-http://127.0.0.1:8080}" > evidence/contest/load-50.json
docker-compose exec -T db psql -v ON_ERROR_STOP=1 -U heatroute -d heatroute -c 'SELECT count(*) FROM calculation; SELECT count(*) FROM input_feature;' > evidence/contest/database-counts.txt
echo 'PASS: environment and user-selected cost-policy checks completed'
