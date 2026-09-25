---
id: B-28
title: "The heap runs away under the reference load: an epoch that sweeps nothing doubles the target"
status: wip
priority: P0
size: M
stage: stage-6-capacity
epic: feature-memory-limit
blocked_by: [B-17]
---

# B-28 — The heap runs away under the reference load

**Feature:** [feature-memory-limit](../features/feature-memory-limit.md).

Found in B-17 on the two-host stand (4 cores, 7.7 GB): kesh at an eighth of §5a — `used_memory`
about 1.2 GB, resident 3.1 GB after the load — was **OOM-killed at 5.8 GB resident** within a minute
of the reference load. At a sixteenth it survived, with peak resident at 4.9 × `used_memory`, where
B-11 measured 2.8 × with no traffic. The collector's log of the eighth (the runtime-logs build):

- epoch 30, after the load: 15.7 M objects marked, alive 1.28 GB, target 2.56 GB;
- epoch 31, under the load: started at 90.2 s, finished at 94.8 s, **20.9 M objects kept and 13 924
  swept** — the 5.3 M objects allocated while it marked counted as alive; alive 2.59 GB, **target 5.17 GB**;
- the heap then grows toward that target, and resident memory passes the host.

**Mechanism, as far as the log shows it:** allocation during a concurrent mark survives that epoch,
and the scheduler sets the next target at twice what survived. The server allocates about **1.1 M
objects a second at 5 000 operations a second** — some 200 per operation — so a mark of a few
seconds under load is enough to double the heap's target. *Hypothesis to confirm, not yet a fact:*
whether a second epoch brings the target back before the host runs out, and whether it is the
allocation rate (fixable in kesh) or the scheduler's rule (`GC.targetHeapUtilization`, the runtime).

- **Why P0:** it invalidates B-16's derived memory limit (2.8 × `maxmemory` with a 20 % drift
  placeholder) under traffic, and any capacity claim in B-17's report beyond a sixteenth.
- AC: the reference load at an eighth runs for ten minutes on a 7.7 GB host without being killed,
  and the report says what bounded the heap — a lower allocation rate, a runtime setting, or both.
- AC: the chart's memory limit is re-derived from resident memory measured *under* the reference
  load, and B-16's placeholder says what replaced it.

## Code anchors

| Module | Path |
|---|---|
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/connection/Connection.kt` |
| bench | `bench/load/stand.sh` |
| deploy | `deploy/chart/values.yaml` |

## Iteration 1 (2026-09-25)

- **Reproduced on the build machine with kesh pinned to 4 cores** (`bench/load/runaway.sh`, pipeline
  1, an eighth): peak resident 5.45 GB; alive 1.28 → 1.87 GB in one epoch that swept 3.4 M objects;
  an epoch of 14 s. **Not reproduced on 20 cores at pipeline 16**: alive steady at 1.6 GB, target 3.2 GB,
  peak 4.2 GB for 180 s. So the runaway needs a slow mark — few cores, many live objects — *and* a high
  allocation rate at once.
- **The allocation is per round trip, not per command**: 264 objects a command at pipeline 1, 52 at
  pipeline 16 — some 226 per read-and-write cycle and 38 per command. A `SET` alone at pipeline 1
  (`redis-benchmark`, 300 000 requests, 32-byte values) allocated **8.4 GB: 28 KB a request**.
- **Where: kotlinx-io on Native does not pool its 8 KB segments.** kesh's transport is `ktor-network`,
  whose channels are kotlinx-io buffers; in kotlinx-io 0.9.1 (the version kesh resolves),
  `core/native/src/SegmentPool.kt` has `MAX_SIZE = 0`, `take()` = `Segment.new()`, and `recycle()` empty.
  Every read and every write takes fresh 8 KB segments, garbage by the next one.

## Decision (owner, 2026-09-25)

**Option 1: kesh's own transport on `epoll`** (research D-31) — no upstream issue for kotlinx-io.
The event loop's thread is the store thread; the RESP and the HTTP port both move onto it, so
`ktor-network` leaves the process. Kept: B-02's limits, B-16's drain, B-15's probes.

Research: [research-architecture](../research/research-architecture.md) R-7, D-25.
