---
id: B-25
title: "BGSAVE through fork, with the child's collector assists off"
status: wip
priority: P2
size: M
stage: stage-4-persistence
blocked_by: [B-14]
---

# B-25 — BGSAVE through fork, with the child's collector assists off

**Feature:** [feature-snapshots](../features/feature-snapshots.md).

B-14's fork probe (research R-6): a forked child of the Kotlin/Native runtime hangs in its first
mutator assist, and writes a whole snapshot at `SAVE`'s speed once a finite `GC.maxHeapBytes` turns
the assists off; `fork()` holds the parent 27–94 ms at 1/16–1/8 of the reference dataset. v1 ships
`SAVE` only because two things the probe did not exercise decide whether this is safe:

- **Locks held at the fork.** The server has ktor's selector and IO threads. If one holds an allocator,
  runtime or stdio lock when the store thread forks, the child deadlocks on it. Test: fork the running
  server under load repeatedly (hundreds of times) and require every child to finish.
- **The child's heap without a collector.** It grows by what the writer allocates; the writer can be
  made allocation-light (no per-value lists), and the growth measured at scale.

And the parent: copy-on-write under writes, measured as resident memory during a background save.
Reaping stays `waitpid(WNOHANG)` from the periodic work — no `SIGCHLD` handler (research R-3).

- AC: `BGSAVE` answers `+Background saving started`, the snapshot it writes is whole and equal to the
  dataset at the fork, and `LASTSAVE` moves when it ends.
- AC: 500 `BGSAVE`s against a server under load: every child finishes; the parent's resident memory
  during one is reported at 1/8 of the dataset or more.

## Code anchors

| Module | Path |
|---|---|
| bench | `bench/src/nativeMain/kotlin/io/github/youndie/kesh/bench/fork/Main.kt` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/persistence/` |

Research: [research-architecture](../research/research-architecture.md).
