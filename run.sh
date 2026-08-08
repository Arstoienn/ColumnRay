#!/usr/bin/env bash
# Compile and run. Arguments are passed straight through to engine.Main, for example:
#   ./run.sh                                  open the windows, load maps/school.json
#   ./run.sh maps/school.json --shot a.png    headless, write a single screenshot
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out
javac -d out $(find src -name '*.java')
exec java -cp out engine.Main "$@"
