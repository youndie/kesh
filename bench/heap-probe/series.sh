#!/usr/bin/env bash
# B-19's measurement series on the build machine: arms × encodings × scales × repeats, each a fresh
# process, one at a time. Writes raw stdout/stderr under OUT and prints one table row per run.
#
#   bench/heap-probe/series.sh OUT "arm encoding scale repeat [rate]" ...
#   arms: default | pmcs | single-mark
#
# A run starts only when the host is quiet — at least MIN_FREE_MB available and a 1-minute load
# average under MAX_LOAD — and otherwise waits; a pause measured beside another resident process is
# not a measurement of the subject (research §1.2, consequence 5).
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=$1; shift
MIN_FREE_MB=${MIN_FREE_MB:-9000}
MAX_LOAD=${MAX_LOAD:-1.5}
mkdir -p "$OUT"

declare -A OPTION=([default]="" [pmcs]="gc=pmcs" [single-mark]="gcMarkSingleThreaded=true")
build() { # arm -> path of its binary
  local arm=$1 dir=bench/build/bin/linuxX64/heapProbeReleaseExecutable
  if [ -n "${OPTION[$arm]}" ]; then
    ./gradlew --console=plain -q :bench:linkHeapProbeReleaseExecutableLinuxX64 "-Pkesh.probeBinary=${OPTION[$arm]}" >&2
  else
    ./gradlew --console=plain -q :bench:linkHeapProbeReleaseExecutableLinuxX64 >&2
  fi
  cp "$dir/kesh-heap-probe.kexe" "$OUT/probe-$arm.kexe"
  echo "$OUT/probe-$arm.kexe"
}
quiet() {
  local free load
  free=$(free -m | awk '/Mem:/{print $7}')
  load=$(awk '{print $1}' /proc/loadavg)
  awk -v f="$free" -v l="$load" -v mf="$MIN_FREE_MB" -v ml="$MAX_LOAD" 'BEGIN{exit !(f>=mf && l<ml)}'
}

declare -A BINARY
for spec in "$@"; do
  read -r arm encoding scale repeat rate <<<"$spec"
  if [ -z "${BINARY[$arm]:-}" ]; then
    # Not `gradlew --stop`: it would also stop another session's daemons of the same Gradle version on
    # this shared machine. An idle daemon costs memory, not CPU; the quiet check covers the rest.
    BINARY[$arm]=$(build "$arm")
  fi
  base="$OUT/$arm-$encoding-$scale${rate:+-rate$rate}-r$repeat"
  until quiet; do echo "waiting for a quiet host: $(free -m | awk '/Mem:/{print $7}') MB free, load $(awk '{print $1}' /proc/loadavg)" >&2; sleep 60; done
  "${BINARY[$arm]}" --encoding "$encoding" --scale "$scale" --seconds 60 ${rate:+--rate "$rate"} >"$base.out" 2>"$base.err"
  python3 bench/heap-probe/pauses.py "$base.out" "$base.err" --label "$arm $encoding $scale${rate:+ rate $rate} r$repeat" | tail -1
done
md5sum "$OUT"/probe-*.kexe
