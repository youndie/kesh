#!/usr/bin/env bash
# Resident memory against used_memory on the reference dataset (B-11, research D-10).
#
#   bench/memory/ratio.sh <scale> [runs]
#
# Loads the reference dataset at <scale> into a fresh kesh through `redis-cli --pipe` (the protocol
# path, not an in-process shortcut), lets it sit idle for the collector to run, and reads the
# process's resident memory (VmRSS, VmHWM) and kesh's own `used_memory`. Prints one line per run.
#
# Needs: the release binaries of kesh and kesh-dataset built, docker (for redis-cli), and a host with
# room — it refuses to start below MIN_FREE_MB available.
set -euo pipefail
SCALE=${1:?scale, e.g. 0.0625}
RUNS=${2:-1}
PORT=${PORT:-16399}
MIN_FREE_MB=${MIN_FREE_MB:-3000}
IDLE_SECONDS=${IDLE_SECONDS:-45}
KESH=server/build/bin/linuxX64/releaseExecutable/kesh.kexe
DATASET=bench/build/bin/linuxX64/releaseExecutable/kesh-dataset.kexe
cli() { docker run --rm -i --network host redis:7.2 redis-cli -p "$PORT" "$@"; }

free_mb=$(free -m | awk '/Mem:/{print $7}')
if [ "$free_mb" -lt "$MIN_FREE_MB" ]; then
  echo "refusing: ${free_mb} MB available, ${MIN_FREE_MB} MB wanted" >&2
  exit 2
fi
echo "host: ${free_mb} MB available, load $(cut -d' ' -f1 /proc/loadavg); kesh $(md5sum "$KESH" | cut -c1-8)"
for run in $(seq 1 "$RUNS"); do
  KESH_PORT=$PORT KESH_HTTP_PORT=off "$KESH" > /tmp/ratio-kesh.log 2>&1 &
  pid=$!
  sleep 1
  started=$(date +%s)
  "$DATASET" --seed 42 --scale "$SCALE" --out - 2> /tmp/ratio-summary.txt | cli --pipe > /tmp/ratio-pipe.txt
  loaded=$(( $(date +%s) - started ))
  sleep "$IDLE_SECONDS"
  used=$(cli INFO memory | tr -d '\r' | awk -F: '/^used_memory:/{print $2}')
  keys=$(cli DBSIZE | tr -d '\r')
  rss=$(awk '/VmRSS/{print $2}' /proc/$pid/status)
  hwm=$(awk '/VmHWM/{print $2}' /proc/$pid/status)
  user=$(awk '/^total/{print $3}' /tmp/ratio-summary.txt)
  python3 - "$SCALE" "$run" "$keys" "$used" "$rss" "$hwm" "$loaded" "$user" <<'P'
import sys
scale, run, keys, used, rss, hwm, loaded, user = sys.argv[1:]
used, rss, hwm = int(used), int(rss) * 1024, int(hwm) * 1024
mb = lambda b: f"{b / 1048576:,.0f} MB"
print(f"scale {scale} run {run}: {int(keys):,} keys loaded in {loaded} s; used_memory {mb(used)}, "
      f"RSS {mb(rss)} (x{rss / used:.2f}), peak RSS {mb(hwm)} (x{hwm / used:.2f}); "
      f"user bytes {mb(int(user))}, used_memory x{used / int(user):.2f} of them")
P
  kill -INT "$pid"; wait "$pid" || true
done
