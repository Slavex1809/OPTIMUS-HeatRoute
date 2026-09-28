#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -B -ntp -DargLine=-Xmx3g -f backend/pom.xml clean verify
cp backend/target/optimus-heatroute-*.jar release/optimus-heatroute.jar
printf 'Built release/optimus-heatroute.jar with tests.\n'
