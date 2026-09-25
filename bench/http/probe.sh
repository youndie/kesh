#!/usr/bin/env bash
# The HTTP port through the release binary (B-15): /metrics checked by Prometheus's own promtool, and
# readiness before and after SIGTERM — kore's announce stage must turn it 503 while liveness stays 200.
# Needs the release binary (./gradlew :server:linkReleaseExecutableLinuxX64), curl, nc and Docker.
set -u
cd "$(dirname "$0")/../.."
B=server/build/bin/linuxX64/releaseExecutable/kesh.kexe
PORT=16500
HTTP=16580
KESH_PORT=$PORT KESH_HTTP_PORT=$HTTP KESH_BIND=127.0.0.1 KESH_DIR=/tmp "$B" > /tmp/kesh-probe.log 2>&1 &
pid=$!
for _ in $(seq 50); do curl -s -o /dev/null "http://127.0.0.1:$HTTP/health/live" && break; sleep 0.1; done
printf '*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n*2\r\n$3\r\nGET\r\n$1\r\nk\r\n' | timeout 2 nc -q1 127.0.0.1 $PORT > /dev/null
curl -s "http://127.0.0.1:$HTTP/metrics" > /tmp/kesh-metrics.txt
echo "--- promtool check metrics:"
docker run --rm -i --entrypoint promtool prom/prometheus:v2.53.0 check metrics < /tmp/kesh-metrics.txt
echo "promtool exit=$?"
echo "--- ready before SIGTERM: $(curl -s -w ' %{http_code}' "http://127.0.0.1:$HTTP/health/ready")"
kill -TERM $pid
sleep 0.3
echo "--- ready after SIGTERM: $(curl -s -w ' %{http_code}' "http://127.0.0.1:$HTTP/health/ready")"
echo "--- live after SIGTERM: $(curl -s -w ' %{http_code}' "http://127.0.0.1:$HTTP/health/live")"
wait $pid
echo "exit=$?"
tail -12 /tmp/kesh-probe.log
