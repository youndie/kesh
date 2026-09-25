---
id: B-17
title: "Reference load report on the reference host"
status: done
priority: P1
size: M
stage: stage-6-capacity
epic: feature-operations
blocked_by: [B-03, B-12, B-14, B-22]
---

# B-17 — Reference load report on the reference host

**Feature:** [feature-operations](../features/feature-operations.md).

The capacity v1 is built for (brief §2, D-4), reported per D-9: the reference dataset loaded and
served at the reference load on the reference host.

- **One subject per host** (research §1.2, consequence 5): the generator runs on another machine,
  and no second resident process shares the subject's host — a co-resident process was measured to
  inflate pause p99 several-fold on this platform.
- Throughput, p50/p99/p99.9 per command, resident memory, `used_memory` and thread count, with the
  raw generator output committed (`kesh-load`, below). Pipelines 1 and 16, 50 connections, Zipf keys.
- **The host** (B-22, research Q-3): the build machine in a quiet window — not a reference host,
  and the report says so in its first line. Load and resident processes on both the Linux and the
  Windows side are read before and after each run and committed with it; a run with other work
  resident is discarded. The generator runs on another machine; which one is asked of the owner
  when the item starts, if it is one of the measurement hosts.

- AC: The report exists, names the host, the build and the commit, and commits the raw generator output.
- AC: It states whether the store thread (research D-14) or the network was the limit.

## Code anchors

| Module | Path |
|---|---|
| bench | `bench/profiles/` |
| bench | `bench/reports/` |

## Decisions (owner, 2026-09-25)

- **Hosts: kesh on bench-a, the generator on bench-b** — the portfolio's two-host stand (4 cores,
  7.7 GB each, a private network between them). The owner offered it, or the build machine loading
  itself; two hosts keep one subject per host (research §1.2, consequence 5) and keep latency off the
  build machine's slow monotonic clock. The cost: 7.7 GB holds a fraction of §5a (B-11's peak ratio
  2.8), so the report is at a stated scale, not the reference dataset's.
- **bench-a's other resident process, another project's database container, is stopped for the
  runs** and started again after.
- **The load comes from kesh's own generator, not `memtier_benchmark`** — a deviation from this item's
  text. memtier derives keys from a number; 10 M of §5a's 15.8 M keys are `session:<uuid>` and the
  counters are `rate:<id>:<minute>`, so memtier could not produce §5a's mix against this dataset.
  `kesh-load` rebuilds the dataset's keys from the seed instead. Its raw output is what is committed.

## Findings (2026-09-25)

- **AC 1 — met, at a sixteenth.** `bench/reports/b-17/`: host, build (commit `943fddd`, md5s), the
  generator's per-operation output for every run, host readings. kesh served 6.4–6.7 k operations/s
  at pipeline 1 and 56–57 k at pipeline 16; Redis 7.2 on the same host and generator 14.9–15.4 k and
  132–136 k. p50 5.2–5.4 ms and 10 ms; **p99.9 1.0–1.3 s** — the collector's assists (R-7), two
  hundred times the median.
- **AC 2 — met: neither the store thread nor the network.** The store thread (now named
  `kesh-store`) used a quarter of a core at pipeline 1 and half at 16; the same link carried 2.3 × as
  much for Redis; the host was half idle. The limit is kesh's own I/O layer: 1.9 cores spread over
  ktor's pool of some 70 threads, and a hand-off to the store thread and back per batch (D-14, D-30).
- **At an eighth, kesh was OOM-killed** at 5.8 GB resident within a minute: an epoch under load kept
  everything allocated during its mark and doubled the heap's target (5.17 GB). **B-28, P0.** At a
  sixteenth the peak was 5.3 × `used_memory`, where B-11 measured 2.8 × idle — B-16's memory limit is
  too low under traffic.
- **The generator is kesh's own, `kesh-load`** (decision above; D-30): it rebuilds the dataset's keys
  from the seed. Its ceiling is above kesh's (135 k/s against Redis); its own collector puts a floor of
  about 0.1 s under p99.9, which shows against Redis — kesh's tail is ten times that.
- **Not covered:** the reference host and dataset; bucket-level histograms for the series (a path bug,
  fixed; the percentiles are the record); an open-loop load.

Research: [research-architecture](../research/research-architecture.md).
