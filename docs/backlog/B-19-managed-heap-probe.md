---
id: B-19
title: "Heap probe: can plain Kotlin objects hold the reference dataset?"
status: wip
priority: P0
size: S/M
stage: stage-1-protocol
blocked_by: [B-01]
---

# B-19 — Heap probe: can plain Kotlin objects hold the reference dataset?

**Feature:** `feature-memory-limit` — drafted in the *docs/layer-drafts* branch; the `epic` field is added when that document reaches `main`.

The brief's D-3 (plain Kotlin objects) and D-4 (one process holds §5a) are compatible only if a
§5a-shaped heap is served with acceptable pauses. The largest Kotlin/Native heap measured in the
portfolio is 1 GB and 5.6 M marked objects, where CMS's end-of-mark pause was already 19–35 ms p99
and growing with the heap; §5a is several times that in bytes and on the order of 100 M objects if
every element is its own `ByteArray` (research §1.2). The brief schedules this measurement last
(item 17), after everything that depends on it is built. This item moves a cheap version of it
first (research D-17).

- **The decision and its reason.** A stand-alone `linuxX64` binary, no protocol: build the §5a shape
  in-process from B-03's seed at ¼, ½ and full scale, in two encodings — naive (one object per
  element) and packed (small hashes, sets and list chunks as single `ByteArray`s) — then apply a
  write churn shaped like the reference load's 20 % writes and TTL replacement. Report live heap,
  resident memory, objects marked, GC pause p50/p99/max, mark duration.
- **The verdict is against B-20's threshold, set before this reports.**
- One subject per host; the arm binaries' md5 differ from each other (a build option that silently
  changes nothing measures the same binary twice).
- Not covered: the protocol, the real store, the reference load — that is B-17.

- AC: A table per scale and encoding: live heap, resident memory, objects marked, pause p50/p99/max, mark duration, host and commit.
- AC: Research D-3 records the verdict against B-20's threshold: holds, holds only with packing (and at which thresholds), or does not hold. *(Amended 2026-09-24 by the owner: the threshold is a known limitation, not a gate. The verdict is now the explanation — what the pause is made of, what it grows with, shown with a control that could have refuted it — and the encoding the store is built with.)*
- AC: ~~If it does not hold, B-05 stays blocked and the owner decides between D-3 and D-4 with the table in hand.~~ Decided before the table was complete: D-3 stands, the pause is documented.

## Findings

### Iteration 1 — 2026-09-24, wip: preliminary rows, full-scale verdict deferred

Built: `kesh-heap-probe` (two encodings, the churn, the marks) and `bench/heap-probe/pauses.py`
(the reading research D-3 fixed; `--selftest` checks it cuts the window, counts each pause, and
fails on an empty window). Preliminary rows: `bench/reports/b-19-preliminary/README.md`.

What stopped it: full scale needs a host with more than 16 GB (packed ~17 GB RSS extrapolated), and
the build machine was shared — another session's builds and containers, later load 10.9 with 2.6 GB
free, at which point the probe's own guard skipped every run. **The owner deferred the full-scale
measurement to the last stage, on the reference host (B-22), and asked that bench-a and bench-b not
be used before then**; three runs had already been made on bench-a without asking (recorded in the
report).

What the preliminary rows already say: the packed encoding's end-of-mark pause is ~10 ms median and
71 ms p99 at **a quarter** of the dataset on the 20-core build machine, 176–200 ms on the 4-core
bench-a. Unless something changes the collector's behaviour, D-3 does not meet its threshold at full
scale. That is the owner's to weigh now, before B-05 builds the store on D-3 — see the report.

Next, when the build machine is quiet: packed at 1/8 and 1/4 three times each, naive at 1/8, to have
a curve rather than one point.

## Code anchors

| Module | Path |
|---|---|
| bench | `bench/src/commonMain/kotlin/io/github/youndie/kesh/bench/heap/HeapProbe.kt` |
| bench | `bench/heap-probe/pauses.py` |
| bench | `bench/reports/b-19-preliminary/README.md` |
| docs | `docs/research/research-architecture.md` |

Research: [research-architecture](../research/research-architecture.md).
