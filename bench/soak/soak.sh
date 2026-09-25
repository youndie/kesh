#!/usr/bin/env bash
# The 24 h soak (B-18), run on the generator host, detached, so that no other machine has to stay up:
#   KESH_ADDR=<subject address> bench/soak/soak.sh <dir with kesh-load.kexe> [hours] [scale] [pipeline]
# kesh must already be running on the subject with its HTTP port (8080) and loaded at the same scale.
# Each hour: one kesh-load run of 3600 s (its summary and raw histograms), then kesh's /metrics —
# resident memory, used_memory, threads, keys. `soak/hours.tsv` is the table B-18 asks for, a row an hour.
set -u
DIR=$1
HOURS=${2:-24}
SCALE=${3:-0.125}
PIPELINE=${4:-1}
A=$KESH_ADDR
cd "$DIR" || exit 1
mkdir -p soak
printf "hour\tended\tops_per_s\tp50_us\tp99_us\tp999_us\tmax_us\terrors\tresident\tused_memory\tratio\tthreads\tkeys\n" > soak/hours.tsv
for h in $(seq "$HOURS"); do
  ./kesh-load.kexe --host "$A" --port 6379 --scale "$SCALE" --run --pipeline "$PIPELINE" --warmup 0 \
    --duration 3600 --raw "soak/hour-$h.raw" > "soak/hour-$h.txt" 2>&1
  curl -s "$A:8080/metrics" > "soak/metrics-$h.txt" || true
  ops=$(awk '/^throughput/{print $2}' "soak/hour-$h.txt")
  read -r p50 p99 p999 max <<< "$(awk '/^ALL /{print $4, $5, $6, $7}' "soak/hour-$h.txt")"
  errors=$(awk '/^throughput/{print $NF}' "soak/hour-$h.txt")
  res=$(awk '/^kesh_resident_memory_bytes /{print $2}' "soak/metrics-$h.txt")
  used=$(awk '/^kesh_used_memory_bytes /{print $2}' "soak/metrics-$h.txt")
  threads=$(awk '/^kesh_threads /{print $2}' "soak/metrics-$h.txt")
  keys=$(awk '/^kesh_keys /{print $2}' "soak/metrics-$h.txt")
  ratio=$(awk -v r="${res:-0}" -v u="${used:-1}" 'BEGIN{printf "%.2f", r/u}')
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" "$h" "$(date -u +%FT%TZ)" "${ops:-?}" \
    "${p50:-?}" "${p99:-?}" "${p999:-?}" "${max:-?}" "${errors:-?}" "${res:-GONE}" "${used:-?}" "$ratio" \
    "${threads:-?}" "${keys:-?}" >> soak/hours.tsv
  if [ -z "$res" ]; then
    echo "kesh gone after hour $h" >> soak/hours.tsv
    break
  fi
done
echo "done $(date -u +%FT%TZ)" >> soak/hours.tsv
