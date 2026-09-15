#!/usr/bin/env bash
# Compile src/ into out/, but only when a source file has changed since the last build.
#
# run.sh used to delete out/ and compile everything before every run, so every run started a cold
# JVM on a CPU still hot from javac - and benchmarks taken that way spread by a third or more.
# The new classes are compiled beside the old ones and swapped in only once javac has succeeded,
# so a run that starts during a build never sees half of one.
set -euo pipefail
cd "$(dirname "$0")"
if [ ! -f out/.built ] || [ -n "$(find src -name '*.java' -newer out/.built -print -quit)" ]; then
    rm -rf out.tmp
    javac -d out.tmp $(find src -name '*.java')
    touch out.tmp/.built
    rm -rf out
    mv out.tmp out
fi
