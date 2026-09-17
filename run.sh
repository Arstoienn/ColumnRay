#!/usr/bin/env bash
# Compile and run. Arguments are passed straight through to engine.Main, for example:
#   ./run.sh                                  open the windows, load maps/school.json
#   ./run.sh maps/school.json --shot a.png    headless, write a single screenshot
set -euo pipefail
cd "$(dirname "$0")"
./build.sh                     # compiles only when a source changed
# JAVA_OPTS reaches the JVM, which is where the bake is tuned from:
#   JAVA_OPTS=-Dlight.texel=1.2 ./run.sh maps/school.json            coarser texels, a faster bake
# --enable-native-access: Keys reads the physical key state from macOS through the FFM API.
exec java --enable-native-access=ALL-UNNAMED ${JAVA_OPTS:-} -cp out engine.Main "$@"
