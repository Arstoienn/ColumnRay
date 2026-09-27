#!/usr/bin/env bash
# Compile and run. Arguments are passed straight through to game.Main, for example:
#   ./run.sh                                  open the windows, load maps/school.json
#   ./run.sh maps/school.json --shot a.png    headless, write a single screenshot
#   ./run.sh --glfw                           the GLFW window instead of the AWT one
set -euo pipefail
cd "$(dirname "$0")"
./build.sh                     # compiles only when a source changed
CP="out:$(ls lib/*.jar | tr '\n' ':')"
# JAVA_OPTS reaches the JVM, which is where the bake is tuned from:
#   JAVA_OPTS=-Dlight.texel=1.2 ./run.sh maps/school.json            coarser texels, a faster bake
# --enable-native-access: Keys reads the physical key state from macOS through the FFM API.
# memoryBackend=ffm: LWJGL otherwise reaches for sun.misc.Unsafe, which is on its way out of Java.
OPTS=(--enable-native-access=ALL-UNNAMED -Dorg.lwjgl.system.memoryBackend=ffm)
# GLFW must own the first thread of the process on macOS, and AWT wants the same one - so this is
# asked for only when --glfw is, and the two windows cannot be open in one JVM. The flag has to be
# read here rather than by Options, because it is a JVM argument and Options runs too late.
for arg in "$@"; do
    if [ "$arg" = "--glfw" ]; then
        [ "$(uname -s)" = Darwin ] && OPTS+=(-XstartOnFirstThread)
        # AWT is still loaded, to rasterise the overlay the game draws - but only ever offscreen.
        OPTS+=(-Djava.awt.headless=true)
        break
    fi
done
exec java "${OPTS[@]}" ${JAVA_OPTS:-} -cp "$CP" game.Main "$@"
