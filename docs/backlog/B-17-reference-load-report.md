---
id: B-17
title: "Reference load report on the reference host"
status: question
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
  raw `memtier_benchmark` output committed. Pipelines 1 and 16, 50 connections, Zipf keys.
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

## Question (for the owner, 2026-09-25)

The subject is decided (B-22: the build machine in a quiet window). The load generator must run on
another machine — one subject per host (research §1.2, consequence 5), and the build machine's slow
monotonic clock must not time the latency. The machines there are:

1. **bench-a or bench-b** — 4 cores, 7.7 GB, on the same network as the build machine; memtier and
   the generator's pipelines fit easily. They carry other measurements, and using them was to be
   asked (the owner, 2026-09-24: "defer to the last stage" — this is the last stage).
2. **The Mac** — no other host needed, but a laptop's network path and power management are in the
   latency it reports; the report would have to say so.
3. **Wait** for a host that is neither.

Research's recommendation: **1**, one of the two for the length of the runs (about an hour for the
profiles at pipeline 1 and 16), with nothing else of the other measurements scheduled on it meanwhile.

Research: [research-architecture](../research/research-architecture.md).
