#!/usr/bin/env bash
# The reference load report (B-17) on a two-host stand, run from a third machine over ssh: kesh on the
# subject host, kesh-load on the generator host, then Redis 7.2 on the same subject host through the
# same generator as the reference point. One subject at a time; a warm-up round is discarded.
#
#   SUBJECT=<ssh host> SUBJECT_ADDR=<its address on the stand's network> GENERATOR=<ssh host> \
#   KESH=<local kesh.kexe> LOAD=<local kesh-load.kexe> bench/load/stand.sh <out dir> [scale] [rounds]
#
# Records per run: the generator's summary and raw histograms, the store thread's and the process's
# CPU, kesh's metrics and peak resident memory, and both hosts' load before and after. Stops the
# subject host's other resident container (named in OTHER, if any) for the runs and starts it after.
set -u
OUT=$1
SCALE=${2:-0.0625}
ROUNDS=${3:-2}
WARMUP=${WARMUP:-10}
DURATION=${DURATION:-60}
OTHER=${OTHER:-}
S=$SUBJECT
G=$GENERATOR
A=$SUBJECT_ADDR
W=kesh-b17
mkdir -p "$OUT"
ssh "$S" "mkdir -p $W"
ssh "$G" "mkdir -p $W"
ssh "$S" "cat > $W/kesh.kexe && chmod +x $W/kesh.kexe" < "$KESH"
ssh "$G" "cat > $W/kesh-load.kexe && chmod +x $W/kesh-load.kexe" < "$LOAD"
{
  echo "date: $(date -u +%FT%TZ)"
  echo "kesh md5: $(ssh "$S" md5sum $W/kesh.kexe | cut -d' ' -f1)"
  echo "kesh-load md5: $(ssh "$G" md5sum $W/kesh-load.kexe | cut -d' ' -f1)"
  for h in subject generator; do
    host=$S
    [ $h = generator ] && host=$G
    echo "== $h: $(ssh "$host" 'echo "$(nproc) cores, kernel $(uname -r)"; free -m | sed -n 2p; uptime')"
  done
  echo "scale $SCALE, rounds $ROUNDS, warm-up ${WARMUP}s, measured ${DURATION}s, 50 connections"
} > "$OUT/stand.txt"

# Ticks per thread of the subject process: "name tid ticks".
cpu_threads() {
  ssh "$S" "pid=\$(pgrep -x $1 | head -1); for t in /proc/\$pid/task/*; do echo \"\$(tr ' ' _ < \$t/comm) \$(basename \$t) \$(cut -d' ' -f14,15 \$t/stat | awk '{print \$1+\$2}')\"; done"
}

# A host's busy and total jiffies.
host_busy() {
  ssh "$1" "head -1 /proc/stat | awk '{b=\$2+\$3+\$4+\$7+\$8; print b, b+\$5+\$6}'"
}

# $1 arm, $2 port, $3 process name (empty for Redis).
run_arm() {
  local arm=$1 port=$2 proc=$3
  ssh "$G" "cd $W && ./kesh-load.kexe --host $A --port $port --scale $SCALE --load" > "$OUT/$arm-load.txt" 2>&1
  ssh "$G" "cd $W && ./kesh-load.kexe --host $A --port $port --scale $SCALE --run --pipeline 1 --warmup 5 --duration 20" \
    > "$OUT/$arm-warmup.txt" 2>&1
  for r in $(seq "$ROUNDS"); do
    for p in 1 16; do
      local tag=$arm-p$p-r$r s0 g0 t0 s1 g1 t1
      [ -n "$proc" ] && cpu_threads "$proc" > /tmp/b17-cpu0
      s0=$(host_busy "$S")
      g0=$(host_busy "$G")
      t0=$(date +%s.%N)
      ssh "$G" "cd $W && ./kesh-load.kexe --host $A --port $port --scale $SCALE --run --pipeline $p --warmup $WARMUP --duration $DURATION --raw $tag.raw" \
        > "$OUT/$tag.txt" 2>&1
      t1=$(date +%s.%N)
      s1=$(host_busy "$S")
      g1=$(host_busy "$G")
      ssh "$G" "cat $W/$tag.raw" > "$OUT/$tag.raw"
      {
        echo "subject host busy: $(echo "$s0 $s1" | awk '{printf "%.0f%%", ($3-$1)/($4-$2)*100}') of $(ssh "$S" nproc) cores"
        echo "generator host busy: $(echo "$g0 $g1" | awk '{printf "%.0f%%", ($3-$1)/($4-$2)*100}') of $(ssh "$G" nproc) cores"
        if [ -n "$proc" ]; then
          cpu_threads "$proc" > /tmp/b17-cpu1
          local secs
          secs=$(echo "$t1 - $t0" | bc)
          echo "cores used by thread over the run, warm-up included (100% = one core):"
          join -1 2 -2 2 <(sort -k2 /tmp/b17-cpu0) <(sort -k2 /tmp/b17-cpu1) |
            awk -v t="$secs" '{d=$5-$3; tot+=d; if ($2=="kesh-store") st=d; printf "%s %s %.0f%%\n", $2, $1, d/t}
                             END {printf "PROCESS all %.0f%%\nSTORE kesh-store %.0f%%\n", tot/t, st/t}' |
            sort -k3 -n -r | head -8
          ssh "$S" "grep -E 'VmHWM|VmRSS|Threads' /proc/\$(pgrep -x $proc | head -1)/status; curl -s $A:8080/metrics | grep -E '^kesh_(used_memory_bytes|resident_memory_bytes|threads|keys) '"
        else
          ssh "$S" "docker exec redis-ref redis-cli -h $A -p $port INFO memory | grep -E '^used_memory:|^used_memory_rss:|^used_memory_peak:'"
        fi
      } > "$OUT/$tag.host.txt"
      echo "$tag: $(grep throughput "$OUT/$tag.txt")"
    done
  done
}

[ -n "$OTHER" ] && ssh "$S" "docker stop $OTHER > /dev/null"
ssh "$S" "pkill -x kesh.kexe; docker rm -f redis-ref > /dev/null 2>&1; true"
ssh "$S" "cd $W && (KESH_BIND=$A KESH_PORT=6379 KESH_HTTP_PORT=8080 KESH_DIR=/tmp setsid ./kesh.kexe > kesh.log 2>&1 < /dev/null &)"
sleep 1
run_arm kesh 6379 kesh.kexe
ssh "$S" "pkill -x kesh.kexe; sleep 1; true"
ssh "$S" "docker run -d --name redis-ref --network host redis:7.2 redis-server --port 6380 --bind $A --save '' --appendonly no > /dev/null"
sleep 1
run_arm redis 6380 ""
ssh "$S" "docker rm -f redis-ref > /dev/null"
[ -n "$OTHER" ] && ssh "$S" "docker start $OTHER > /dev/null"
echo "done: $OUT"
