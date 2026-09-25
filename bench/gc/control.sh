#!/usr/bin/env bash
# B-31's control: do the pauses kesh exports on /metrics match the runtime's own log, epoch for epoch?
# Needs a build with the runtime's GC log: ./gradlew :server:linkReleaseExecutableLinuxX64 -Pkesh.runtimeLogs=true
# kesh runs with KESH_GC_LOG=on — one line per exported collection — under the reference load at
# pipeline 16 for SECONDS, in a scope capped at 64 tasks and 6 GB; then its /metrics is read and the
# three are compared. One host: this checks a transcription, not a latency.
#   KESH=<runtime-logs kesh.kexe> bench/gc/control.sh <out dir> [scale] [seconds]
set -u
OUT=$1
SCALE=${2:-0.015625}
SECONDS_=${3:-60}
PORT=${PORT:-16903}
HTTP=${HTTP:-18933}
LOAD=bench/build/bin/linuxX64/loadReleaseExecutable/kesh-load.kexe
mkdir -p "$OUT"
KESH_PORT=$PORT KESH_HTTP_PORT=$HTTP KESH_DIR=/tmp KESH_GC_LOG=on systemd-run --user --scope --quiet \
  -p TasksMax=64 -p MemoryMax=6G -p MemorySwapMax=0 "$KESH" > "$OUT/kesh.log" 2>&1 < /dev/null &
for _ in $(seq 100); do nc -z 127.0.0.1 $PORT 2>/dev/null && break; sleep 0.1; done
"$LOAD" --port $PORT --scale "$SCALE" --load > "$OUT/load.txt" 2>&1
"$LOAD" --port $PORT --scale "$SCALE" --run --pipeline 16 --warmup 0 --duration "$SECONDS_" >> "$OUT/load.txt" 2>&1
# Two more polls' worth, so the last collection of the run has become the runtime's "last" one.
sleep 1
curl -s 127.0.0.1:$HTTP/metrics > "$OUT/metrics.txt"
pkill -x kesh.kexe
sleep 6
python3 - "$OUT" <<'P'
import re, sys
out = sys.argv[1]
log = open(f"{out}/kesh.log", errors="replace").read()
runtime = {}
for m in re.finditer(r"Epoch #(\d+): Mutators pause time #([12]): (\d+) microseconds", log):
    runtime.setdefault(int(m.group(1)), {})[int(m.group(2))] = int(m.group(3))
kesh = {}
for m in re.finditer(r"kesh: gc epoch (\d+): pause #1 (\d+) µs, pause #2 (?:(\d+) µs|none)", log):
    kesh[int(m.group(1))] = {1: int(m.group(2)), **({2: int(m.group(3))} if m.group(3) else {})}
metrics = open(f"{out}/metrics.txt").read()
def value(name):
    m = re.search(rf"^{re.escape(name)} (\S+)$", metrics, re.M)
    return float(m.group(1)) if m else None
missed = value("kesh_gc_epochs_missed_total")
collections = value("kesh_gc_collections_total")
first_sum = value('kesh_gc_pause_seconds_sum{pause="first"}')
second_sum = value('kesh_gc_pause_seconds_sum{pause="second"}')
differ = [e for e in kesh if runtime.get(e) != kesh[e]]
absent = [e for e in runtime if e not in kesh and e <= max(kesh, default=0)]
print(f"runtime epochs {len(runtime)} (#{min(runtime, default=0)}–#{max(runtime, default=0)}), kesh exported {len(kesh)}")
print(f"epochs whose pauses differ from the runtime's: {len(differ)} {differ[:10]}")
print(f"epochs up to kesh's last absent from kesh's lines: {len(absent)} {absent[:10]}; kesh_gc_epochs_missed_total {missed}")
print(f"kesh_gc_collections_total {collections}; kesh lines {len(kesh)}")
print(f"pause #1 sum: metrics {first_sum} s, kesh lines {sum(v[1] for v in kesh.values()) / 1e6} s")
print(f"pause #2 sum: metrics {second_sum} s, kesh lines {sum(v.get(2, 0) for v in kesh.values()) / 1e6} s")
ok = not differ and missed == len(absent) and collections == len(kesh)
print("CONTROL " + ("PASSES" if ok else "FAILS"))
sys.exit(0 if ok else 1)
P
