#!/usr/bin/env bash
# B-23: writes growing the keyspace to 16 M keys, with the collector's mutator assists on and off.
#
#   bench/growth/assists.sh [runs] [keys]
#
# Needs kesh built with the collector's log (`./gradlew :server:linkReleaseExecutableLinuxX64
# -Pkesh.runtimeLogs=true`) and docker for redis-benchmark. Interleaves the arms, `runs` of each. Per
# run: the longest stall — from the log, the time from an epoch's first "Pausing the mutators" to its
# end — the longest stop-the-world pause, the peak resident memory, the run's length. The runtime's
# log reads the monotonic clock; each run measures that clock against the wall clock and corrects by
# it (the build machine's runs ~10 % slow). A watchdog kills kesh above LIMIT_MB of resident memory,
# so that an arm that overshoots does not take the host's other work down with it.
set -euo pipefail
RUNS=${1:-2}
KEYS=${2:-16000000}
PORT=${PORT:-16396}
MIN_FREE_MB=${MIN_FREE_MB:-8000}
LIMIT_MB=${LIMIT_MB:-8000}
KESH=server/build/bin/linuxX64/releaseExecutable/kesh.kexe
echo "kesh $(md5sum "$KESH" | cut -c1-8), $KEYS keys, runs $RUNS, watchdog at $LIMIT_MB MB"
for run in $(seq 1 "$RUNS"); do
  for arm in on off; do
    free_mb=$(free -m | awk '/Mem:/{print $7}')
    if [ "$free_mb" -lt "$MIN_FREE_MB" ]; then echo "skip $arm run $run: ${free_mb} MB available"; continue; fi
    host="${free_mb} MB available, load $(cut -d' ' -f1 /proc/loadavg)"
    log=/tmp/assists-$arm-$run.log
    KESH_PORT=$PORT KESH_HTTP_PORT=off KESH_GC_ASSISTS=$arm "$KESH" > "$log" 2>&1 &
    pid=$!
    sleep 1
    clock0=$(python3 -c 'import time; print(time.time(), time.monotonic())')
    ( while kill -0 $pid 2>/dev/null; do
        rss=$(awk '/VmRSS/{print int($2/1024)}' /proc/$pid/status 2>/dev/null || echo 0)
        if [ "${rss:-0}" -gt "$LIMIT_MB" ]; then echo "WATCHDOG $rss MB" >> "$log"; kill -9 $pid; fi
        sleep 0.5
      done ) &
    watchdog=$!
    started=$(date +%s.%N)
    docker run --rm --network host redis:7.2 redis-benchmark -p $PORT -t set -n "$KEYS" -r 2000000000 -P 16 -c 50 -q < /dev/null > /tmp/assists-bench.log 2>&1 || true
    seconds=$(python3 -c "import time; print(round(time.time() - $started, 1))")
    clock1=$(python3 -c 'import time; print(time.time(), time.monotonic())')
    hwm=$(awk '/VmHWM/{print int($2/1024)}' /proc/$pid/status 2>/dev/null || echo 0)
    keys=$(docker run --rm --network host redis:7.2 redis-cli -p $PORT DBSIZE < /dev/null 2>/dev/null || echo "?")
    kill -INT $pid 2>/dev/null || true; wait $pid 2>/dev/null || true
    kill $watchdog 2>/dev/null || true
    python3 - "$log" "$arm" "$run" "$host" "$seconds" "$hwm" "$keys" "$clock0" "$clock1" <<'P'
import re, sys
log, arm, run, host, seconds, hwm, keys, c0, c1 = sys.argv[1:]
w0, m0 = map(float, c0.split()); w1, m1 = map(float, c1.split())
factor = (w1 - w0) / (m1 - m0)          # wall seconds per monotonic second
end, first_pause, stw = {}, {}, 0
for line in open(log, errors="replace"):
    t = re.search(r"\]\[(\d+\.\d+)s\]", line)
    if not t: continue
    at = float(t.group(1))
    if m := re.search(r"Pausing the mutators until epoch (\d+)", line):
        first_pause.setdefault(int(m.group(1)), at)
    elif m := re.search(r"Epoch #(\d+): Finished", line):
        end[int(m.group(1))] = at
    elif m := re.search(r"Mutators pause time #\d: (\d+) microseconds", line):
        stw = max(stw, int(m.group(1)))
stalls = [end[e] - t for e, t in first_pause.items() if e in end]
longest = max(stalls) * factor if stalls else 0
watchdog = " WATCHDOG" if "WATCHDOG" in open(log, errors="replace").read() else ""
print(f"assists {arm:3} run {run}: {keys} keys in {seconds} s; {len(stalls)} assisted epochs, longest stall "
      f"{longest:.2f} s; longest stop-the-world {stw * factor / 1000:.1f} ms; peak RSS {hwm} MB; "
      f"clock factor {factor:.3f}; host {host}{watchdog}")
P
  done
done
