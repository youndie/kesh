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
- AC: Research D-3 records the verdict against B-20's threshold: holds, holds only with packing (and at which thresholds), or does not hold.
- AC: If it does not hold, B-05 stays blocked and the owner decides between D-3 and D-4 with the table in hand.

## Code anchors

| Module | Path |
|---|---|
| bench | `bench/heap-probe/` |
| docs | `docs/research/research-architecture.md` |

Research: [research-architecture](../research/research-architecture.md).
