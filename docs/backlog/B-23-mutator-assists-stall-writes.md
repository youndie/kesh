---
id: B-23
title: "Writes stall for seconds while the keyspace grows: decide on the collector's mutator assists"
status: done
priority: P1
size: S
stage: stage-3-memory
blocked_by: []
---

# B-23 — Writes stall for seconds while the keyspace grows: decide on the collector's mutator assists

**Feature:** `feature-memory-limit` — drafted in the *docs/layer-drafts* branch; the `epic` field is added when that document reaches `main`.

Found in B-05 (research §1.2, correction found in B-05). Under a write-heavy load that grows the
keyspace, every Kotlin thread is stopped until the collector's running mark finishes — the runtime's
*mutator assists*, on by default. The mark is concurrent and its stop-the-world pauses stay under
3 ms, but the assist turns the whole mark into a stall: 1.6–4.2 s per epoch at 12–27 M live objects,
growing with them. A `redis-benchmark` growth to 16 M keys saw 0.1 % of `SET`s over 1.6 s and some
over 3 s. Steady-state churn over a heap that does not grow (B-19) did not show it.

- **The lever.** Assists are on only while `GC.autotune` is on and `GC.maxHeapBytes` is unbounded
  (`JetBrains/kotlin@v2.4.20!/kotlin-native/runtime/src/gcScheduler/common/cpp/GCSchedulerConfig.hpp`,
  `mutatorAssists()`); there is no binary option. A finite `GC.maxHeapBytes` turns them off — and
  B-11's `maxmemory` may set one anyway (research §1.2, consequence 4).
- **The price.** Without assists nothing slows allocation while a mark runs: the heap overshoots its
  target by whatever is written during the mark, and resident memory with it. The runtime's own note
  on that path is "figure out what to do with OOMs".

- AC: The same 16 M growth, assists on and off (one of the levers above), interleaved, on a host with
  at least 8 GB free and nothing else resident; the longest stall per arm taken from the runtime's GC
  log (mark duration after "Pausing the mutators"), not from `redis-benchmark`, whose histogram stops
  at 3 s; the peak resident memory per arm.
- AC: The build machine's clock is corrected for or the host is another one (the bench service's
  quirks: its monotonic clock runs ~10 % slow).
- AC: The decision is recorded in research as a D-entry with both numbers; if assists go off, the
  store document says how the overshoot interacts with `maxmemory` (B-11).

## Code anchors

| Module | Path |
|---|---|
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/Main.kt` |
| server | `server/build.gradle.kts` |
| bench | `bench/growth/assists.sh` |

## Findings (2026-09-25)

- **The lever is built**: `KESH_GC_ASSISTS=off` sets a finite `GC.maxHeapBytes` at startup; a binary
  built with `-Pkesh.runtimeLogs=true` logs the collector. `bench/growth/assists.sh` interleaves the
  arms, reads the longest stall and pause from the log, corrects them by the wall clock measured in
  the same run, and kills kesh above 8 GB.
- **Measured on the build machine** (two interleaved pairs, 9.5–11 GB available, load 1.7–4.9, another
  session's builds resident): assists **on** — longest stall 8.54 s, stop-the-world at most 4.3 ms,
  peak resident 4.9–6.4 GB, 16 M keys in 160 s. Assists **off** — no stall, and the heap past 8 GB in
  29 s, before half the keys were in; the watchdog stopped it both times (heap 7.9 GB after epoch 24).
- **Decision, research D-25: the assists stay on.** The overshoot is unbounded; a
  `maxmemory` cannot hold a heap the collector cannot brake.
- **Not met as written:** "a host with at least 8 GB free and **nothing else resident**" — the build
  machine had other work running, and the off arm needs more than 8 GB to finish at all.

## Decision (owner, 2026-09-25)

Accepted as measured: D-25 is final, the assists stay on, and `KESH_GC_ASSISTS=off` stays in the
binary for later measurements. Waiting for a quiet host was declined — no host turns the off arm's
unbounded heap into a bounded one.

Research: [research-architecture](../research/research-architecture.md).
