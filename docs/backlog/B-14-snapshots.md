---
id: B-14
title: "Snapshots: the format, SAVE, load at startup, torn-save safety, and their measured cost"
status: done
priority: P1
size: L
stage: stage-4-persistence
epic: feature-snapshots
blocked_by: [B-10]
---

# B-14 — Snapshots: the format, SAVE, load at startup, torn-save safety, and their measured cost

**Feature:** [feature-snapshots](../features/feature-snapshots.md).

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
| bench | `bench/snapshot/check.py` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/persistence/` |

## Findings (2026-09-25)

- **Built on B-13's branch** (itself on B-11's): loading goes through the accounting and the expiry
  index. It merges after them.
- **AC 1 — met at 1/16 and 1/8** of the reference dataset, through the running server
  (`bench/snapshot/check.py`): round trip — `DBSIZE` equal, 10 000 sampled keys read in full equal,
  every `ZCARD` equal; torn save — `SIGKILL` during `SAVE`, the next start loads the previous
  snapshot; expired keys not saved. Full scale waits for B-22's host. `SnapshotTest` holds the
  format: every kind, cut files and a damaged byte refused.
- **AC 2 — met** (research R-5): `SAVE` 2.3 s and 4.5 s, snapshots 271 MB and 542 MB, loads 6 s and
  12 s at 1/16 and 1/8 — linear; some 36 s, 4.3 GB and 98 s at full scale by arithmetic.
- **AC 3 — met** (research R-6, D-24): no `BGSAVE` in v1. The fork probe measured the fork (27–94 ms)
  and found the child **hangs** in its first mutator assist — and **works** with the assists off.
  Not shipped for two untested hazards; B-25 carries them.
- **Decided on the way** (D-24): the snapshot loads before the listener binds — the brief's refusal
  over Redis's `-LOADING`; a snapshot that cannot be read stops the start with exit status 1.
- **Mutations, each caught:** writing the snapshot in place instead of beside it (the torn-save run's
  restart refused a cut snapshot); the CRC check removed (`SnapshotTest`).

Research: [research-architecture](../research/research-architecture.md).
