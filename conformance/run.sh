#!/usr/bin/env bash
# Runs the conformance suite: four servers — kesh and the oracle, each once open and once with a
# password — then the harness, then everything is stopped. On the Linux build machine.
#
#   conformance/run.sh            build kesh, run every script under conformance/scripts
#
# The oracle is the `redis:7.2` image with conformance/oracle/redis.conf (research D-16). Correctness
# runs may share a host; nothing timed here means anything.
set -euo pipefail
cd "$(dirname "$0")/.."

PASSWORD=conformance-secret
ORACLE=16391
ORACLE_LOCKED=16392
KESH=16393
KESH_LOCKED=16394
IMAGE=redis:7.2

./gradlew --console=plain -q :server:linkReleaseExecutableLinuxX64
BINARY=server/build/bin/linuxX64/releaseExecutable/kesh.kexe

pids=()
# kesh stops through kore's plan, whose announce stage keeps the port open for five seconds (see
# services/server.md). Returning before it exits made the next run's kesh die on EADDRINUSE while the
# old one answered the port check — so cleanup waits, and startup refuses a port already taken.
cleanup() {
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
  for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done
  docker rm -f kesh-oracle kesh-oracle-locked >/dev/null 2>&1 || true
}
trap cleanup EXIT

for port in $ORACLE $ORACLE_LOCKED $KESH $KESH_LOCKED; do
  if nc -z 127.0.0.1 "$port" 2>/dev/null; then echo "port $port is already taken; is another run still stopping?"; exit 1; fi
done

# The configuration file is the one source; it is passed as arguments rather than mounted, because
# the image's entrypoint runs Redis as its own user, which cannot read a file synced with mode 0600.
config=()
while read -r key value; do
  [[ -z "$key" || "$key" == \#* ]] && continue
  value=${value#\"}; value=${value%\"}
  config+=("--$key" "$value")
done < conformance/oracle/redis.conf

oracle() { # name port [extra args]
  local name=$1 port=$2; shift 2
  docker rm -f "$name" >/dev/null 2>&1 || true
  docker run -d --rm --name "$name" --network host "$IMAGE" \
    redis-server "${config[@]}" --port "$port" "$@" >/dev/null
}
oracle kesh-oracle "$ORACLE"
oracle kesh-oracle-locked "$ORACLE_LOCKED" --requirepass "$PASSWORD"
KESH_PORT=$KESH KESH_BIND=127.0.0.1 "$BINARY" >/tmp/kesh-conformance.log 2>&1 & pids+=($!)
KESH_PORT=$KESH_LOCKED KESH_BIND=127.0.0.1 KESH_PASSWORD=$PASSWORD "$BINARY" >/tmp/kesh-conformance-locked.log 2>&1 & pids+=($!)

for port in $ORACLE $ORACLE_LOCKED $KESH $KESH_LOCKED; do
  for _ in $(seq 1 50); do nc -z 127.0.0.1 "$port" 2>/dev/null && break; sleep 0.2; done
  sleep 0.1
  nc -z 127.0.0.1 "$port" 2>/dev/null || { echo "nothing listens on $port"; exit 1; }
done

./gradlew --console=plain -q :conformance:runJvm --args="\
--kesh 127.0.0.1:$KESH --oracle 127.0.0.1:$ORACLE \
--kesh-locked 127.0.0.1:$KESH_LOCKED --oracle-locked 127.0.0.1:$ORACLE_LOCKED \
--scripts $PWD/conformance/scripts"
