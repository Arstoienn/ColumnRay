#!/usr/bin/env bash
# Compile src/ into out/, but only when a source file has changed since the last build.
#
# run.sh used to delete out/ and compile everything before every run, so every run started a cold
# JVM on a CPU still hot from javac - and benchmarks taken that way spread by a third or more.
# The new classes are compiled beside the old ones and swapped in only once javac has succeeded,
# so a run that starts during a build never sees half of one.
set -euo pipefail
cd "$(dirname "$0")"
# The one dependency: LWJGL, the window and the GL entry points. Fetched rather than committed;
# see lib/fetch.sh, which checks the hash of every jar it writes.
./lib/fetch.sh
# Java's classpath separator is a colon everywhere but Windows, where this runs under Git Bash and
# so has a Unix shell in front of a Windows JVM. test.sh says the same thing at more length.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
    *) SEP=':' ;;
esac
CP=$(ls lib/*.jar | tr '\n' "$SEP")
# The parentheses matter: without them -newer binds only to the *.jar branch, every .java file
# matches the first one, and -print never runs - so the test comes out empty and nothing is ever
# rebuilt. That shipped for about ten minutes and cost a confusing stack trace.
if [ ! -f out/.built ] || [ -n "$(find src lib \( -name '*.java' -o -name '*.jar' \) -newer out/.built -print -quit)" ]; then
    rm -rf out.tmp
    javac -cp "$CP" -d out.tmp $(find src -name '*.java')
    touch out.tmp/.built
    rm -rf out
    mv out.tmp out
fi
