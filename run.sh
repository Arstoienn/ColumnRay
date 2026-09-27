#!/usr/bin/env bash
# Compile and run. Arguments are passed straight through to game.Main, for example:
#   ./run.sh                                  open the windows, load maps/school.json
#   ./run.sh maps/school.json --shot a.png    headless, write a single screenshot
set -euo pipefail
cd "$(dirname "$0")"
./build.sh                     # compiles only when a source changed
# Java's classpath separator is a colon everywhere but Windows, where this runs under Git Bash and
# so has a Unix shell in front of a Windows JVM. test.sh says the same thing at more length.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
    *) SEP=':' ;;
esac
CP="out${SEP}$(ls lib/*.jar | tr '\n' "$SEP")"
# JAVA_OPTS reaches the JVM, which is where the bake is tuned from:
#   JAVA_OPTS=-Dlight.texel=1.2 ./run.sh maps/school.json            coarser texels, a faster bake
# --enable-native-access: Keys reads the physical key state from macOS through the FFM API.
# memoryBackend=ffm: LWJGL otherwise reaches for sun.misc.Unsafe, which is on its way out of Java.
OPTS=(--enable-native-access=ALL-UNNAMED -Dorg.lwjgl.system.memoryBackend=ffm)
# GLFW owns the context now - the hidden window the engine draws into, and the one on the screen -
# and on macOS GLFW must have the process's first thread. AWT wants that thread for its own windows,
# so it does not get one: it is an offscreen rasteriser here, which is all Game.overlay needs.
case "$(uname -s)" in
    Darwin) OPTS+=(-XstartOnFirstThread -Djava.awt.headless=true) ;;
esac
exec java "${OPTS[@]}" ${JAVA_OPTS:-} -cp "$CP" game.Main "$@"
