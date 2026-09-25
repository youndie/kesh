---
id: B-14
title: "Snapshots: the format, SAVE, load at startup, torn-save safety, and their measured cost"
status: wip
priority: P1
size: L
stage: stage-4-persistence
blocked_by: [B-10]
---

# B-14 — Snapshots: the format, SAVE, load at startup, torn-save safety, and their measured cost

**Feature:** `feature-snapshots` — drafted in the *docs/layer-drafts* branch; the `epic` field is added when that document reaches `main`.

The dataset has to survive a restart, and at ~4.3 GB how long that takes is part of the design.

- **The decision and its reason.** kesh's own format, written to a temporary file and renamed into
  place; expired keys skipped; TTLs stored as absolute times. The server is not ready until the load
  completes.
- **`SAVE` and load are measured on the reference dataset** (research R-5), because B-16 derives the
  chart's grace period and the startup probe's budget from these two numbers.
- **`BGSAVE` is decided here** (research R-6), with its cost measured: a pause-and-copy, versioned
  values, or incremental. A fork-based design has to answer two things first: what a forked child
  of a multi-threaded Kotlin/Native runtime can safely do, and how it is reaped without a `SIGCHLD`
  handler (a handler interrupts the selector's `pselect`, research R-3).

- AC: The round-trip and torn-save scenarios of `feature-snapshots` pass on the reference dataset.
- AC: `SAVE` duration, snapshot size and load duration on the reference dataset are written into research R-5 with the host they were taken on.
- AC: Research R-6 records the `BGSAVE` decision, the alternative rejected, and the measured cost.

## Code anchors

| Module | Path |
|---|---|
| snapshot | `snapshot/src/` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/persistence/` |

Research: [research-architecture](../research/research-architecture.md).
