---
id: B-17
title: "Reference load report on the reference host"
status: open
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

Research: [research-architecture](../research/research-architecture.md).
