---
id: B-25
title: "BGSAVE through fork, with the child's collector assists off"
status: done
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

## Findings

### Iteration 1 — 2026-09-25

`BGSAVE` written (`Persistence.backgroundSave`, reaped by `waitpid(WNOHANG)` from the periodic work,
killed before the stop's own save; `INFO`'s `rdb_bgsave_in_progress`, `rdb_last_bgsave_status`,
`rdb_last_bgsave_time_sec`, `rdb_current_bgsave_time_sec`) with `BackgroundSaveTest`; it compiles.
Replies read from `redis/redis@7.2!/src/rdb.c` (`bgsaveCommand`, `saveCommand`). `wait4` and
`struct rusage` do not resolve in the linuxX64 platform library, so the child's peak resident memory
is for the bench script to sample from `/proc`, not for kesh to report.

**Stopped:** the build machine stopped answering (not even ping) during the first run of
`BackgroundSaveTest`, before any result was read. Whether the test caused it is not known — the next
run goes under a process and memory ceiling (`TasksMax`, `MemoryMax`) and a timeout, so a fork that
runs away cannot take the machine down again.

### Iteration 2 — 2026-09-25

The build machine came back at 15:40 with the same boot: its journal is silent from 15:25:23 to
15:40:19 with no OOM and no kernel message — the host was suspended, not crashed. The first run had
hung on the test itself (it waited for 20 lines of a 12-line `INFO persistence`), before the host went.

- `BackgroundSaveTest` green, three tests. Mutations: the stop not killing the child — caught by
  `a background save still running at the stop …`; no refusal while a child runs — caught by
  `BGSAVE writes the dataset as it was at the fork …`. **The child's `GC.maxHeapBytes` line survives:**
  with the writer below, the child allocates too little at 300 000 keys to reach an assist, so no test
  here can see the hang the line prevents; its evidence is B-14's probe (a child that allocated hung at
  its first assist, every run).
- **The writer copied every element** out of a packed collection (`Packed.read`), and lists and sorted
  sets went through `range()` lists and `Pair`s: at 1/64 the child's private dirty memory reached
  636 MB for an 88 MB snapshot. The packed layout is already the snapshot's blob layout, so the writer
  now reads slices where the store keeps them (`ByteSlice`, `ScoredSlice`).
- 500 `BGSAVE`s at 1/64 under the reference load at pipeline 16 (old writer, build machine, kesh in a
  scope of 64 tasks and 8 GB): **500 ok, 0 failed, 0 hung**; median 1.15 s; `fork()` held the parent
  11 ms median, 25 ms max; the last snapshot loaded, 246 903 keys. The parent's resident memory went
  from 511 to 813 MB over the run, the load writing all along; the next run samples `used_memory` to
  tell the two apart.
- **Stopped at 1/8:** the build machine had 6 GB available when the run began and 188 MB, with 7 GB
  of swap in use, twenty seconds into the load — another project's Gradle build and a shared daemon
  (3.5 GB swapped). The run was killed before its first save; a memory figure from a swapping host
  counts what is resident, not what is used. Still to take, on a quiet host: 1/8 with the old writer
  (kept on the build machine as `~/kesh-b25-old-writer.kexe`, md5 `789ad031…`), 1/8 with the new one,
  and the 500 saves again on the final binary with `used_memory` sampled.

### Iteration 3 — 2026-09-25: done

- **AC 1 — met.** `BGSAVE` answers `+Background saving started` (Redis 7.2's bytes, with its syntax
  error and "already in progress": `conformance/scripts/server/bgsave.redis`, all 21 scripts agree);
  the snapshot is the dataset at the fork and `LASTSAVE` moves when the child ends
  (`BackgroundSaveTest`, three tests; two of three mutations killed, the third recorded above).
- **AC 2 — met.** 500 `BGSAVE`s against the server under the reference load, on the final binary: 500
  ok, 0 failed, 0 hung; 500 more on the first binary, the same. At 1/8, the parent's resident memory
  during a save is 3.2–4.4 GB and the two processes together peak at 5.7 GB (Pss), `used_memory`
  1.23 GB. `bench/reports/b-25/`.
- Found: a background save under load needs about 1.4 × `used_memory` beyond the server's own —
  the parent's collector writes into every live object, so copy-on-write copies its heap. Above the
  chart's 3.3 ×: **B-29**, a question for the owner.
- Where it ran: the build machine (WSL2, 20 cores, 16 GB), kesh in a scope capped at 64 tasks and
  8 GB. Not the reference host, not full scale.
