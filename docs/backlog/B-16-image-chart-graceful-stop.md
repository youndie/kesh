---
id: B-16
title: "Image, chart, and a graceful stop that survives being repeated"
status: done
priority: P1
size: M
stage: stage-5-operations
epic: feature-operations
blocked_by: [B-14, B-15]
---

# B-16 — Image, chart, and a graceful stop that survives being repeated

**Feature:** [feature-operations](../features/feature-operations.md).

kesh deploys like the portfolio's other native services: a minimal image and a Helm chart with one
StatefulSet replica and a persistent volume for snapshots.

- **Grace period and startup budget are derived, not chosen** (research R-5): from B-14's measured
  `SAVE` and load times, with the arithmetic written as a comment next to the values in the chart.
- **The container's memory limit is derived too**: `maxmemory` times B-11's measured ratio, plus the
  drift B-18 will measure (a placeholder until then, labelled as one).
- **The graceful-stop scenario is run repeatedly under load, not once** (research R-3): a signal
  that lands on the selector thread kills the process through `pselect`'s `EINTR`, and one green run
  cannot show that it does not. The number of runs is in the pull request.

- AC: The graceful-stop scenario of `feature-operations` passes in the chart on a test cluster, repeatedly, with 200 connected clients and none receiving a truncated reply.
- AC: The chart's grace period, startup probe budget and memory limit each carry the measurement they were derived from.

## Code anchors

| Module | Path |
|---|---|
| deploy | `deploy/Dockerfile` |
| deploy | `deploy/chart/` |

## Findings (2026-09-25)

- **AC 1 — met, 30 of 30.** `bench/drain/run.sh` on a kind cluster on the build machine: the chart,
  200 connections pipelining `INCR`, `SIGTERM` by deleting the pod. No torn reply, no client error,
  kore's plan reached `EXIT`, and the counter the drain saved equalled the replies the clients got in
  every run — 16.8 million replies in all; drains of 39–85 ms (`bench/reports/b-16/`).
- **AC 2 — met.** The rendered StatefulSet carries each derivation beside its value: grace period
  from the `SAVE` time (B-14), startup budget from the load time (B-14), memory limit from the peak
  ratio (B-11) with the drift a labelled placeholder until B-18 ([deploy](../services/deploy.md)).
  kesh is told the grace period, and kore refuses at startup a drain that does not fit it — seen on
  the release binary: exit 1, both numbers named.
- **What the drain was, and is** (research D-29): stopping cancelled every connection, so a batch
  could run and lose its replies — `DrainTest`'s ledger caught 4–12 unanswered `INCR`s in each of
  three runs of that mutant. Now only the wait for a read is interrupted; a batch read is answered
  under `NonCancellable`, then the snapshot is saved (`KESH_SAVE_ON_SHUTDOWN`), inside the drain
  stage so kore does not triple its budget.
- **A torn reply never appeared, even in the mutant**: ktor queues a whole reply before `writeFully`
  returns. Its other side: **a non-reading client's replies are buffered without bound** — 100 MB
  queued, drain done in 1.4 ms. Redis's default for normal clients is unbounded too; B-27's
  subscriber limit is told to count bytes, not wait for backpressure.
- **On the way:** the image (sborka's reference Dockerfile, distroless, user 65532, 47 MB); an empty
  kesh measured 30.6 MB resident, 9 threads. The first 30-run series was lost to a harness bug (a
  pod log written over the series log) and is not counted; the harness was fixed and rerun.
- **Not covered:** a full-size save inside the grace period on a real host (arithmetic from B-14,
  B-22's host); the 5 s cut-off for a write that suspends (no run reaches it); a registry for the
  image and environments (brief §7).

**From B-14 (research R-5):** `SAVE` ≈ 2.3 s per million keys of the reference dataset and loading
≈ 6 s per million, linear — at full scale some 36 s and 98 s. The grace period and the startup probe
budget start from those, on B-22's host.

Research: [research-architecture](../research/research-architecture.md).
