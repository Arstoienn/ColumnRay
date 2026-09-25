#!/usr/bin/env bash
# Everything that says whether this build is the same engine as the last one.
#
#   ./test.sh                unit tests, determinism, then the golden frames
#   ./test.sh --unit         unit tests only
#   ./test.sh --determinism  the same build must render the same frames on one thread and on all
#   ./test.sh --golden       the frames must match tests/golden/
#   ./test.sh --haven        the same, against the maps/haven submodule (--flat, so no bake)
#   ./test.sh --gpu          the card's frame against the CPU's, through the whole frame loop
#   ./test.sh --bless        rewrite tests/golden/ from this build (read the diff first)
#
# The golden files hold digests of the renderer's own pixels, depth, albedo and lightmap - not of
# the PNGs, which an encoder is free to write differently between JDK releases without a pixel
# changing. See Capture.verify and tests/golden/README.md.
#
# Every mode but --gpu runs on the CPU, whatever the machine has: --verify takes the CPU path by
# itself (Options says why), so these digests are the double-precision renderer's and stay
# comparable to the ones in tests/golden/ whether or not this machine has a card.
set -euo pipefail
cd "$(dirname "$0")"

mode=all
case "${1:-}" in
    --unit|--determinism|--golden|--haven|--gpu|--bless) mode=${1#--} ;;
    "") ;;
    *) echo "usage: $0 [--unit | --determinism | --golden | --haven | --gpu | --bless]" >&2; exit 2 ;;
esac

# Java's classpath separator is the one thing in here that is not the same everywhere: a colon on
# Unix and a semicolon on Windows, where this runs under Git Bash and so has a Unix shell in front
# of a Windows JVM. Everything else - find, javac, the forward slashes in the paths it prints - the
# JDK and Git Bash agree about.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) CP=';' ;;
    *) CP=':' ;;
esac

# Small enough to run in seconds, large enough that a real change cannot hide in it. The size is
# part of the golden file: change it and every digest changes with it.
SIZE=320x180
VIEWS=tests/views/school.txt
# Haven is a submodule, and the size of it: 3.8 million surfaces and a bake of a quarter of an
# hour. So it is a target of its own, --flat, and CI never sees it.
HAVEN=maps/haven/haven.json
HAVEN_VIEWS=tests/views/haven.txt

./build.sh

run() { java --enable-native-access=ALL-UNNAMED ${JAVA_OPTS:-} -cp out game.Main "$@"; }
frames() { run maps/school.json --verify "$VIEWS" --size "$SIZE" "$@" | grep -E '^(#|view |lightmap )'; }
havenFrames() { JAVA_OPTS="-Xmx12g ${JAVA_OPTS:-}" run "$HAVEN" --verify "$HAVEN_VIEWS" --size "$SIZE" --flat "$@" \
    | grep -E '^(#|view |lightmap )'; }

fail=0

if [ "$mode" = all ] || [ "$mode" = unit ]; then
    echo "== unit =="
    mkdir -p out-test
    javac -d out-test -cp out $(find tests/src -name '*.java')
    java -cp "out${CP}out-test" engine.Tests || fail=1
fi

if [ "$mode" = all ] || [ "$mode" = determinism ]; then
    # Nothing about a frame or a bake may depend on how many cores ran it - that is what the
    # distance tie-break and the cell padding are there for - and this holds on any machine,
    # which the golden files cannot. So it is a test of its own, and CI runs it everywhere.
    echo "== determinism =="
    many=$(JAVA_OPTS="-Dlight.cache=false" frames)
    one=$(JAVA_OPTS="-Dlight.cache=false -Djava.util.concurrent.ForkJoinPool.common.parallelism=1" frames)
    if diff -u <(printf '%s\n' "$many") <(printf '%s\n' "$one"); then
        echo "ok    one thread and all of them agree"
    else
        echo "FAIL  the output depends on how many threads produced it."
        fail=1
    fi
fi

if [ "$mode" = haven ] || { [ "$mode" = bless ] && [ -f "$HAVEN" ]; }; then
    if [ ! -f "$HAVEN" ]; then
        echo "skip  haven: $HAVEN is not there. git submodule update --init maps/haven"
    else
        echo "== haven =="
        # Which map these digests are of, on the line above them. Haven is a submodule, so it can
        # move under the golden file without a source file changing, and then a diff here is the
        # map having been re-exported rather than the engine having drifted - which is a different
        # thing to do about it. That happened once and went unnoticed for a week: the map's grade
        # went from 1.0/0.08 to 0.8/0.0 and every camera's pixels with it, while the file here
        # still held the old map's, because nothing runs --haven unless somebody types it.
        got=$(printf '# maps/haven %s\n%s' "$(git -C maps/haven rev-parse HEAD 2>/dev/null || echo unknown)" "$(havenFrames)")
        if [ "$mode" = bless ]; then
            printf '%s\n' "$got" > tests/golden/haven-flat.txt
            echo "blessed tests/golden/haven-flat.txt"
        elif diff -u tests/golden/haven-flat.txt <(printf '%s\n' "$got"); then
            echo "ok    haven-flat"
        else
            echo "FAIL  haven-flat: if only the maps/haven line moved, the map was re-exported and" >&2
            echo "      this wants ./test.sh --bless in the commit that moves the submodule pointer." >&2
            echo "      Otherwise see the note under the school golden failure; the same applies." >&2
            fail=1
        fi
    fi
fi

if [ "$mode" = all ] || [ "$mode" = golden ] || [ "$mode" = bless ]; then
    echo "== golden =="
    golden() {   # golden <name> <extra flags...>
        local name=$1; shift
        local got
        got=$(frames "$@")
        if [ "$mode" = bless ]; then
            printf '%s\n' "$got" > "tests/golden/$name.txt"
            echo "blessed tests/golden/$name.txt"
            return 0
        fi
        if diff -u "tests/golden/$name.txt" <(printf '%s\n' "$got"); then
            echo "ok    $name"
        else
            cat >&2 <<MSG

FAIL  $name: the frames are not the ones in tests/golden/$name.txt.
      Every optimisation in this engine is meant to be provably free. If this change was
      meant to alter the picture, read the diff above, check it is only what you intended,
      and run ./test.sh --bless. If it was not, you have found a bug.
      A first run on a new platform can differ for a duller reason: Math.sin and friends are
      allowed a last-place error that varies by CPU. tests/golden/README.md says which
      platform these were taken on.
MSG
            return 1
        fi
    }
    golden school || fail=1
    golden school-flat --flat || fail=1
    golden school-hdr --hdr || fail=1
fi

# The card, if this machine has one. Not part of the default run and not in CI: it needs an
# OpenGL context, and a card works in float where the renderer works in double, so there is
# nothing here a digest could hold. What it checks instead is that the frame the game shows is
# the frame the CPU would have drawn, through the whole loop - the pitch warp, the overscan
# growing under it, the card's buffers being rebuilt around that - at five tilts per camera.
if [ "$mode" = gpu ]; then
    echo "== gpu =="
    # --window at the render size so that DynamicResolution's ladder lands on sizes worth
    # checking rather than on eight steps between 960 and 1920: the rungs are then 160 to 320
    # columns, three of them odd, and the whole sweep stays as quick as the rest of this script.
    run maps/school.json --gpu-verify "$VIEWS" --size "$SIZE" --window "$SIZE" || fail=1
fi

exit $fail
