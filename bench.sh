#!/usr/bin/env bash
# Time a map the same way every time, and say how far to trust the number.
#
#   ./bench.sh                                          school, 640x360, 5 runs
#   ./bench.sh ../ColumnRay-Haven/haven.json 854x480 8  haven, 854x480, 8 runs
#   CP=old/classes CP_B=new/classes ./bench.sh ...      A/B: two builds, run alternately
#   JAVA_OPTS_B=-XX:ActiveProcessorCount=4 ./bench.sh   A/B: one build, two sets of JVM options
#   REST=60 ./bench.sh ...                              seconds to let the CPU cool between runs
#   EXTRA=--cpu ./bench.sh ...                          time the CPU shading path instead of the card
#
# Each run is a fresh JVM that warms the JIT up with untimed frames and then times every frame of a
# full turn on the spot, level and tilted as far as the map and the buffer allow (Capture.bench):
# what it reports is that run's median and 99th percentile frame. The card shades it, as it does
# everywhere else now; EXTRA=--cpu measures the CPU path, which is what to use when A/B-ing a
# change to the renderer's own shading.
#
# Why the rest, and why A/B. Measured on this machine, a fanless MacBook Air (M3): six identical
# runs, JIT warmed up and medians taken, still spread by 39-55%, and they got slower as the
# afternoon went on - the first three at 10 ms, the last three at 12-16 ms. That is the chip
# throttling as it heats, and nothing inside the JVM can take it out. What can be done is to make
# it fall on both sides of a comparison equally: with CP_B or JAVA_OPTS_B the two are run in
# turn, A B A B, and the verdict is the median of the B/A ratios of neighbouring runs, not a
# difference between two sets of runs taken ten minutes apart.
set -euo pipefail
cd "$(dirname "$0")"
MAP=${1:-maps/school.json}
SIZE=${2:-640x360}
RUNS=${3:-5}
REST=${REST:-30}
CP=${CP:-out}
CP_B=${CP_B:-}
JAVA_OPTS_B=${JAVA_OPTS_B:-}
[ "$CP" = out ] && ./build.sh
AB=0
if [ -n "$CP_B" ] || [ -n "$JAVA_OPTS_B" ]; then AB=1; CP_B=${CP_B:-$CP}; fi
LOG=$(mktemp)
trap 'rm -f "$LOG"' EXIT

one() {   # label classes options
    java --enable-native-access=ALL-UNNAMED $3 -cp "$2" game.Main "$MAP" --flat --size "$SIZE" --bench ${EXTRA:-} \
        2>/dev/null | grep '^BENCH' | sed "s/^/$1 run $r /" | tee -a "$LOG"
}

for r in $(seq 1 "$RUNS"); do
    one A "$CP" "${JAVA_OPTS:-}"
    if [ "$AB" = 1 ]; then
        sleep "$REST"
        one B "$CP_B" "${JAVA_OPTS:-} $JAVA_OPTS_B"
    fi
    [ "$r" -lt "$RUNS" ] && sleep "$REST"
done

python3 - "$LOG" "$AB" <<'EOF'
import sys, statistics
runs = {}
for line in open(sys.argv[1]):
    f = line.split()
    label, r, pitch = f[0], int(f[2]), f[f.index("pitch") + 1]
    runs.setdefault((label, pitch), {})[r] = (float(f[f.index("median") + 1]), float(f[f.index("p99") + 1]))
for (label, pitch), rs in sorted(runs.items(), key=lambda kv: (kv[0][1], kv[0][0])):
    med = [m for m, _ in rs.values()]; p99 = [p for _, p in rs.values()]
    mm = statistics.median(med)
    print("%s pitch %3s deg over %d runs: median %.2f ms (%.0f fps), p99 %.2f ms, run medians %.2f..%.2f = %.0f%% spread"
          % (label, pitch, len(rs), mm, 1000 / mm, statistics.median(p99), min(med), max(med), 100 * (max(med) - min(med)) / mm))
if sys.argv[2] == "1":
    for pitch in sorted({p for _, p in runs}):
        a, b = runs.get(("A", pitch), {}), runs.get(("B", pitch), {})
        ratios = [b[r][0] / a[r][0] for r in sorted(a) if r in b]
        if ratios:
            print("pitch %3s deg: B/A median frame time, run by run %s -> median %.3f (B is %+.0f%%)"
                  % (pitch, " ".join("%.2f" % x for x in ratios), statistics.median(ratios), 100 * (statistics.median(ratios) - 1)))
EOF
