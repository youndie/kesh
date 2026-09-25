# B-16: the graceful stop, 30 times under load

**Result: 30 of 30 runs passed.** 200 connections pipelining `INCR` four at a time, then `SIGTERM`
(the pod deleted, as a rollout does): no connection ended on the bytes of a torn reply, no client
saw an error, kore's plan reached `EXIT` every time, and in every run the counter the drain saved
equalled the `INCR` replies the clients received — 16 783 916 replies in all, 325 592 to 681 364 a
run. The drain stage took 39–85 ms (median 56 ms); kore's announce wait adds 5 s before it.

## How

`bench/drain/run.sh 30 b16` on the build machine (not a reference host — research Q-3), in a kind
cluster (`kind-kesh-drain`, one node), with the image `docker build -f deploy/Dockerfile .` and the
chart at `maxmemory` 256 MiB with `saveOnShutdown` on — a 24 s grace period by the chart's arithmetic.
The clients are `bench/drain/checker.py` in a pod beside kesh; the ledger is read after the restart,
from the snapshot the drain saved. `runs.txt` is the script's own record, one line per run.

## What it does not show

- **Load at scale.** The dataset is a few counters; the save takes milliseconds. A save of a full
  dataset inside the grace period is the chart's arithmetic from B-14's measurement, not this run's.
- **A signal on the selector thread** (research R-3). 30 runs and none died; that is 30 runs, not a
  proof — the mechanism is still there.
- **Clients that stop reading.** The checker reads. A client that does not was tried in a test and
  did not hold the drain at all — ktor queued its 100 MB of replies without suspending (research
  D-29) — so the 5 s cut-off is a guard no run reaches.

An earlier series of 30 also reported 30 of 30, but its per-run lines were lost: the harness wrote
run 30's pod log over the series log (fixed; the pod logs now have their own names). Its 29 surviving
transcripts all reached `EXIT` after a save; it is not counted here.
