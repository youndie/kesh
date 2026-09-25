#!/usr/bin/env bash
# The graceful-stop scenario of feature-operations, repeated (B-16): kesh's chart in a kind cluster,
# 200 connections under load, SIGTERM (the pod deleted, as a rollout does), and for every run
#   - no connection ends with the bytes of a truncated reply,
#   - kore's plan ran to EXIT in the pod's log (no SIGKILL, no crash),
#   - the counter the drain saved equals the INCR replies the clients received (no executed
#     command lost its reply, and the save covered the last one).
# Usage: bench/drain/run.sh <runs> [image tag]. Needs kind, kubectl, helm, docker, and the image.
set -u
cd "$(dirname "$0")/../.."
RUNS=${1:-10}
TAG=${2:-b16}
CLUSTER=kesh-drain
NS=kesh-drain
# Counters are new per invocation: the snapshot keeps the last invocation's, and a reused name would
# add them up.
STAMP=$(date +%s)
kind get clusters | grep -qx $CLUSTER || kind create cluster --name $CLUSTER --wait 120s
kubectl config use-context kind-$CLUSTER > /dev/null
kind load docker-image kesh:$TAG --name $CLUSTER > /dev/null
docker image inspect python:3.12-slim > /dev/null 2>&1 || docker pull -q python:3.12-slim > /dev/null
# Loading a multi-platform image into kind fails on a digest it cannot find (a kind quirk); the node
# pulls it itself when that happens, so a failure here is not the run's.
kind load docker-image python:3.12-slim --name $CLUSTER > /dev/null 2>&1 || true
kubectl create namespace $NS --dry-run=client -o yaml | kubectl apply -f - > /dev/null
kubectl -n $NS create configmap checker --from-file=bench/drain/checker.py --dry-run=client -o yaml | kubectl apply -f - > /dev/null
helm upgrade --install kesh deploy/chart -n $NS --set image.tag=$TAG --set maxmemory=268435456 --wait --timeout 180s > /dev/null
kubectl -n $NS get statefulset kesh -o jsonpath='{.spec.template.spec.terminationGracePeriodSeconds}' | sed 's/^/grace period: /'; echo

resp() { # one command through a throwaway pod: $1 = the inline command. `run -i` can attach after
  # the output was written and return nothing; asked again then, up to five times.
  for _ in 1 2 3 4 5; do
    out=$(resp_once "$1")
    [ -n "$out" ] && { echo "$out"; return; }
  done
}

resp_once() {
  kubectl -n $NS run resp-$RANDOM --rm -i --restart=Never --image=python:3.12-slim --quiet -- \
    python3 -c "import socket;s=socket.create_connection(('kesh',6379));s.sendall(b'$1\r\n');r=s.recv(4096).decode().split('\r\n');print(r[1] if r[0][:1]=='$' and r[0]!='\$-1' else r[0])"
}

# The pod's log per run, and one line per run in the results — under names of their own: an earlier
# version wrote run 30's pod log over a series log called kesh-drain-30.log.
RESULTS=/tmp/kesh-drain-results.txt
: > $RESULTS
pass=0
for run in $(seq "$RUNS"); do
  counter="ledger:$STAMP:$run"
  kubectl -n $NS delete pod checker --ignore-not-found --wait > /dev/null
  kubectl -n $NS run checker --restart=Never --image=python:3.12-slim \
    --overrides='{"spec":{"containers":[{"name":"checker","image":"python:3.12-slim","command":["python3","/c/checker.py","kesh-0.kesh-headless","6379","'"$counter"'","200"],"volumeMounts":[{"name":"c","mountPath":"/c"}]}],"volumes":[{"name":"c","configMap":{"name":"checker"}}]}}' > /dev/null
  until kubectl -n $NS logs checker 2>/dev/null | grep -q loaded; do sleep 0.5; done
  kubectl -n $NS logs -f kesh-0 > /tmp/kesh-drain-pod-$run.log 2>&1 &
  follower=$!
  sleep 3
  kubectl -n $NS delete pod kesh-0 --wait=false > /dev/null
  until kubectl -n $NS get pod checker -o jsonpath='{.status.phase}' | grep -q Succeeded; do sleep 1; done
  result=$(kubectl -n $NS logs checker | tail -1)
  wait $follower
  kubectl -n $NS wait pod/kesh-0 --for=condition=Ready --timeout=180s > /dev/null
  saved=$(resp "GET $counter" | tail -1 | tr -d '\r')
  replies=$(echo "$result" | python3 -c 'import json,sys;print(json.load(sys.stdin)["replies"])')
  truncated=$(echo "$result" | python3 -c 'import json,sys;print(json.load(sys.stdin)["truncated_connections"])')
  exited=$(grep -c "EXIT COMPLETED" /tmp/kesh-drain-pod-$run.log)
  verdict=FAIL
  if [ "$truncated" = 0 ] && [ "$exited" = 1 ] && [ "$saved" = "$replies" ]; then verdict=ok; pass=$((pass + 1)); fi
  echo "run $run: $verdict — $result; saved $saved; transcript $(grep -aE 'DRAIN|EXIT' /tmp/kesh-drain-pod-$run.log | tr '\n' ' ')" | tee -a $RESULTS
done
echo "passed $pass of $RUNS" | tee -a $RESULTS
