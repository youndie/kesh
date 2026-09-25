#!/usr/bin/env bash
# B-28: does the heap run away under the reference load? One host (a memory question, not a latency
# one): kesh built with the collector's log, the dataset at SCALE, then kesh-load at pipeline 16 for
# SECONDS. Resident memory is sampled each second and kesh is killed above LIMIT_MB (a watchdog, as
# bench/growth/assists.sh). Prints the peak, and per epoch of the run: alive, target, objects kept and
# swept — and the objects swept per operation, which is what the load leaves for the collector.
#   bench/load/runaway.sh [scale] [seconds] [limit MB]      (from the repository's root)
set -u
SCALE=${1:-0.125}
SECONDS_=${2:-300}
LIMIT_MB=${3:-8000}
PORT=${PORT:-16900}
PIPELINE=${PIPELINE:-16}
# CORES=0-3 pins kesh to those cores (taskset): a 4-core subject on a bigger host.
CORES=${CORES:-}
KESH=${KESH:-server/build/bin/linuxX64/releaseExecutable/kesh.kexe}
LOAD=bench/build/bin/linuxX64/loadReleaseExecutable/kesh-load.kexe
LOG=/tmp/runaway-gc.log
PIN=()
[ -n "$CORES" ] && PIN=(taskset -c "$CORES")
KESH_PORT=$PORT KESH_HTTP_PORT=off KESH_DIR=/tmp setsid "${PIN[@]}" "$KESH" > $LOG 2>&1 < /dev/null &
pid=$!
sleep 1
"$LOAD" --port $PORT --scale "$SCALE" --load
start=$(grep -ao '\]\[[0-9]*\.[0-9]*s\]' $LOG | tail -1 | tr -d '][s')
peak=0
( while kill -0 $pid 2>/dev/null; do
    rss=$(awk '/VmRSS/{print int($2/1024)}' /proc/$pid/status 2>/dev/null || echo 0)
    [ "$rss" -gt "$LIMIT_MB" ] && { echo "WATCHDOG: $rss MB > $LIMIT_MB MB" >> $LOG; kill -9 $pid; }
    echo "$rss" >> /tmp/runaway-rss.txt
    sleep 1
  done ) &
: > /tmp/runaway-rss.txt
"$LOAD" --port $PORT --scale "$SCALE" --run --pipeline "$PIPELINE" --warmup 0 --duration "$SECONDS_" > /tmp/runaway-load.txt 2>&1 || true
ops=$(awk '/^throughput/{print $2}' /tmp/runaway-load.txt)
hwm=$(awk '/VmHWM/{print int($2/1024)}' /proc/$pid/status 2>/dev/null || echo killed)
kill $pid 2>/dev/null
sleep 1
echo "scale $SCALE, ${SECONDS_}s at pipeline $PIPELINE${CORES:+, kesh on cores $CORES}: $ops operations/s; peak resident ${hwm} MB (sampled max $(sort -n /tmp/runaway-rss.txt | tail -1) MB)"
grep -a WATCHDOG $LOG || true
python3 - "$LOG" "$start" "${ops:-0}" "$SECONDS_" <<'P'
import re, sys
log, start, ops, secs = sys.argv[1], float(sys.argv[2]), float(sys.argv[3] or 0), float(sys.argv[4])
rows, cur = [], {}
for line in open(log, errors="replace"):
    t = re.search(r"\]\[(\d+\.\d+)s\]", line)
    if not t or float(t.group(1)) < start: continue
    if m := re.search(r"alive (\d+), target (\d+)", line):
        cur["alive"], cur["target"] = int(m.group(1)), int(m.group(2))
    elif m := re.search(r"Epoch #(\d+): Sweep: swept (\d+) objects, kept (\d+) objects", line):
        cur.update(epoch=int(m.group(1)), swept=int(m.group(2)), kept=int(m.group(3)))
    elif m := re.search(r"Epoch #(\d+): Finished. Total GC epoch time is (\d+) microseconds", line):
        cur["ms"] = int(m.group(2)) // 1000
        rows.append(cur); cur = {}
print("epoch  alive MB  target MB  kept M  swept M  epoch ms")
for r in rows:
    print(f"{r.get('epoch','?'):>5}  {r.get('alive',0)/1e6:8.0f}  {r.get('target',0)/1e6:9.0f}  {r.get('kept',0)/1e6:6.1f}  {r.get('swept',0)/1e6:7.1f}  {r.get('ms',0):8}")
swept = sum(r.get("swept", 0) for r in rows)
if ops: print(f"objects swept per operation: {swept / (ops * secs):.0f} ({swept/1e6:.1f} M over {ops*secs/1e6:.2f} M operations)")
P
