#!/usr/bin/env bash
# The subject side of the 24 h soak (B-18), run on the subject host from the directory that holds
# kesh-soak.kexe: starts kesh detached — RESP on ADDR:6379, its HTTP port on 8080 — and a sampler that
# appends resident memory to rss.txt every minute and kills kesh above LIMIT_MB, so that a runaway
# ends the soak rather than the host (as bench/load/runaway.sh). Both outlive the ssh session.
#   bench/soak/subject.sh <subject address> [limit MB]
set -u
ADDR=$1
LIMIT_MB=${2:-7000}
KESH_BIND=$ADDR KESH_PORT=6379 KESH_HTTP_PORT=8080 KESH_DIR=. setsid ./kesh-soak.kexe > kesh.log 2>&1 < /dev/null &
sleep 1
pid=$(pgrep -nx kesh-soak.kexe) || { echo "kesh did not start"; cat kesh.log; exit 1; }
setsid bash -c "while kill -0 $pid 2>/dev/null; do
  r=\$(awk '/VmRSS/ {print int(\$2 / 1024)}' /proc/$pid/status 2>/dev/null || echo 0)
  echo \"\$(date -u +%FT%TZ) \$r\" >> rss.txt
  if [ \"\$r\" -gt $LIMIT_MB ]; then echo \"\$(date -u +%FT%TZ) WATCHDOG \$r MB > $LIMIT_MB MB\" >> rss.txt; kill -9 $pid; fi
  sleep 60
done" > /dev/null 2>&1 < /dev/null &
echo "kesh pid $pid; resident memory every minute into rss.txt, killed above $LIMIT_MB MB"
