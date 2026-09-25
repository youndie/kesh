#!/usr/bin/env bash
# B-25: COUNT background saves against kesh under the reference load, on one host (a correctness and
# memory question, not a latency one). kesh with the dataset at SCALE, kesh-load at pipeline 16 for as
# long as the saves take, bench/fork/bgsave.py asking for the saves one after another. Then kesh is
# stopped and started again from the last snapshot: a start that loads it is a snapshot that is whole
# (the count and the CRC, research D-24).
#
# kesh runs in a scope of its own with a ceiling on tasks and memory, so a fork that ran away could
# not take the host with it; and a watchdog kills it if the host's available memory falls under
# MIN_AVAILABLE_MB (1500) for another reason — a host that swaps measures nothing, and can freeze.
#   bench/fork/bgsave.sh [scale] [count] [memory limit, e.g. 12G]      (from the repository's root)
set -u
SCALE=${1:-0.015625}
COUNT=${2:-500}
LIMIT=${3:-12G}
PORT=${PORT:-16901}
KESH=${KESH:-server/build/bin/linuxX64/releaseExecutable/kesh.kexe}
LOAD=bench/build/bin/linuxX64/loadReleaseExecutable/kesh-load.kexe
DIR=$(mktemp -d /tmp/kesh-bgsave-XXXXXX)
LOG=$DIR/kesh.log
start_kesh() {
  KESH_PORT=$PORT KESH_HTTP_PORT=off KESH_DIR=$DIR systemd-run --user --scope --quiet \
    -p TasksMax=64 -p MemoryMax="$LIMIT" -p MemorySwapMax=0 "$KESH" >> "$LOG" 2>&1 < /dev/null &
  # Up to two minutes: kesh listens only once its snapshot has loaded (research D-24) — 12 s at 1/8.
  for _ in $(seq 1200); do
    pid=$(pgrep -nx kesh.kexe) && [ -n "$pid" ] && nc -z 127.0.0.1 $PORT 2>/dev/null && return
    sleep 0.1
  done
  echo "kesh did not start"; tail "$LOG"; exit 1
}
start_kesh
echo "kesh pid $pid, snapshots in $DIR"
( while kill -0 "$pid" 2>/dev/null; do
    available=$(awk '/MemAvailable/ {print int($2 / 1024)}' /proc/meminfo)
    if [ "$available" -lt "${MIN_AVAILABLE_MB:-1500}" ]; then
      echo "WATCHDOG: the host has $available MB available; killed kesh" | tee -a "$LOG"
      kill -9 "$pid"
    fi
    sleep 1
  done ) &
watchdog=$!
"$LOAD" --port $PORT --scale "$SCALE" --load
"$LOAD" --port $PORT --scale "$SCALE" --run --pipeline 16 --warmup 0 --duration 86400 > "$DIR/load.txt" 2>&1 &
load=$!
sleep 5
python3 bench/fork/bgsave.py "$pid" $PORT "$COUNT"
result=$?
kill $load 2>/dev/null
wait $load 2>/dev/null
kill $watchdog 2>/dev/null
grep -c 'terminated with success' "$LOG" | sed 's/^/children that ended with success, by the log: /'
grep -aE 'background saving (error|terminated by signal)|child failed' "$LOG" | head
awk '/fork\(\) took/ {gsub("µs;?","",$NF); print $(NF-1)}' "$LOG" | sort -n |
  awk '{a[NR]=$1} END {if (NR) printf "fork() held the parent: median %d µs, max %d µs over %d forks\n", a[int((NR+1)/2)], a[NR], NR}'
keys=$(printf '*1\r\n$6\r\nDBSIZE\r\n' | nc -q1 127.0.0.1 $PORT | tr -d ':\r')
kill -TERM "$pid"
while kill -0 "$pid" 2>/dev/null; do sleep 0.2; done
start_kesh
after=$(printf '*1\r\n$6\r\nDBSIZE\r\n' | nc -q1 127.0.0.1 $PORT | tr -d ':\r')
grep -a 'loaded' "$LOG" | tail -1
echo "keys before the stop $keys (the load kept writing after the last save), loaded from the last snapshot $after"
kill -TERM "$pid"
exit $result
