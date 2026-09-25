---
id: feature-snapshots
title: Persistence through snapshots
type: feature
status: active
owner: unassigned
involved_services: [store, snapshot, server, bench]
client_entries: []
api: []
tags: [persistence]
---

# Persistence through snapshots

## 1. Overview

The dataset survives a restart. `SAVE` writes a snapshot, `BGSAVE` has a forked child write it while
the server goes on serving, and the server loads it at startup. At the reference dataset's size
(~4.3 GB) how long saving and loading take is a design input: the chart's grace period and startup
budget are derived from it (research R-5). Built in B-14; saving on stop is B-16's, `BGSAVE` B-25's.

## 2. Business rules

* `SAVE` writes the whole dataset, consistent as of the moment it started, to a temporary file, then
  renames it into place atomically. A crash mid-save leaves the previous snapshot intact.
* Keys already expired are not written, and not loaded if they expired since. Remaining TTLs are
  stored as absolute times.
* `LASTSAVE` answers the Unix time of the last successful save — of the start, until the first.
* A failed `SAVE` answers `-ERR`, as Redis's does; the reason is in the log.
* **At startup the server refuses connections until loading finishes** (research D-24) — the
  brief's rule, kept over Redis's `-LOADING` replies: the snapshot is loaded before the listener
  exists. Readiness (B-15) will say the same to the orchestrator.
* A snapshot that is not whole — cut, damaged, not kesh's — stops the start: one line naming the file
  and the reason, exit status 1. Nothing it held is served.
* **`BGSAVE` forks, as Redis's does** (research R-6, B-25): it answers `+Background saving started`,
  and the child writes the dataset as it was at the fork — writes that follow are not in it — through
  the same temporary file and rename as `SAVE`. `LASTSAVE` moves when the child ends with success, not
  when it starts; `INFO persistence`'s `rdb_last_bgsave_status` says `err` after one that did not.
* While a child runs, `BGSAVE` and `SAVE` both answer `-ERR Background save already in progress`.
  Any argument but `SCHEDULE` is `-ERR syntax error`; `SCHEDULE` changes nothing, since kesh has no other
  kind of child to wait for — Redis's reply differs only while an AOF rewrite runs.
* The child is collected by the periodic work, ten times a second — `waitpid(WNOHANG)`, no `SIGCHLD`
  handler (research R-3). A stop kills a running child first and removes its temporary file, as
  Redis's `prepareForShutdown` does, so it cannot race the stop's own save to the rename.
* Saving on `SIGTERM` when configured is B-16's (`KESH_SAVE_ON_SHUTDOWN`). There is no `SHUTDOWN`
  command — ruled out by the owner on 2026-09-25; kore's ordered stop is the only way down.

## 3. The commands this feature adds

| Command | Reply | Notes |
|---|---|---|
| `SAVE` | `+OK`, or `-ERR` on failure | holds the store thread for the whole write |
| `BGSAVE [SCHEDULE]` | `+Background saving started` | `-ERR` if the fork fails; the outcome is in `INFO persistence` and the log |
| `LASTSAVE` | an integer, seconds since the epoch | |

The rest of the server commands is drafted in *endpoint-server*, with B-15 and B-16.

## 4. Code anchors

| Service | Code |
|---|---|
| snapshot | `snapshot/src/commonMain/kotlin/io/github/youndie/kesh/snapshot/Snapshot.kt` — the format |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/persistence/` — the file, `SAVE`, `BGSAVE`, `LASTSAVE` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/KeshServer.kt` — the load before the listener |
| bench | `bench/snapshot/check.py` — the scenarios through the running server |
| bench | `bench/fork/bgsave.sh` — `BGSAVE` again and again under load, with the memory of both processes |

## 5. Scenarios

### Scenario: Round trip
* **Given:** the reference dataset
* **When:** `SAVE`, stop and start
* **Then:** `DBSIZE`, a random sample of 10 000 keys and every sorted set's `ZCARD` match the values before the stop
* **Automated:** `bench/snapshot/check.py` — through the running server at 1/16 and 1/8 of the dataset (987 625 and 1 975 250 keys; every sampled key read in full, unordered replies sorted); the format alone in `snapshot/src/commonTest/kotlin/io/github/youndie/kesh/snapshot/SnapshotTest.kt::every kind comes back as it was`. Full scale is not measured, by the owner's decision (see `backlog.md`).

### Scenario: Torn save
* **Given:** a snapshot on disk
* **When:** the process is killed during `SAVE`
* **Then:** the next start loads the previous snapshot
* **Automated:** `bench/snapshot/check.py` — `SIGKILL` 0.2 s into a `SAVE`; a mutant writing in place instead made the next start refuse a cut snapshot. Refusal of cut and damaged files: `snapshot/src/commonTest/kotlin/io/github/youndie/kesh/snapshot/SnapshotTest.kt::a file cut anywhere is refused`

### Scenario: Expired keys are not saved
* **Given:** `SET k v PX 10` and 20 ms passed without access
* **When:** `SAVE`, stop and start
* **Then:** `EXISTS k` replies `:0`
* **Automated:** `bench/snapshot/check.py`; in the format, `snapshot/src/commonTest/kotlin/io/github/youndie/kesh/snapshot/SnapshotTest.kt::every kind comes back as it was`

### Scenario: A background save is the dataset at the fork
* **Given:** 300 000 keys loaded from a snapshot
* **When:** in one pipeline, `BGSAVE`, `BGSAVE`, `SAVE`, then `SET key:0 changed`, `DEL key:1`, `SET after …`
* **Then:** `+Background saving started`, twice `-ERR Background save already in progress`, and the
  writes answered; `LASTSAVE` moves once the child has ended; a restart from the snapshot has
  300 000 keys, `key:0`'s old value, `key:1`, and no `after`
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/BackgroundSaveTest.kt::BGSAVE writes the dataset as it was at the fork and LASTSAVE moves when it ends`; the replies against Redis 7.2 in `conformance/scripts/server/bgsave.redis`

### Scenario: A stop kills a background save
* **Given:** a `BGSAVE` whose child is still writing
* **When:** the server stops
* **Then:** no child of the process is left, reaped or not, and no temporary file is left beside the snapshot
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/BackgroundSaveTest.kt::a background save still running at the stop is killed and leaves nothing behind`

### Scenario: Every child finishes under load
* **Given:** the reference load at pipeline 16 against the server
* **When:** 500 `BGSAVE`s, one after another
* **Then:** every child ends with `rdb_last_bgsave_status:ok` and moves `LASTSAVE`; the last snapshot loads
* **Automated:** `bench/fork/bgsave.sh` — a run, not a suite: see research R-6 for where and what it found

## 6. Out of scope

* Redis RDB/AOF compatibility, append-only logging (brief §2).

## 7. Quirks

* **`SAVE` blocks all commands for its duration**, as Redis's `SAVE` does: 2.3 s at 1/16 of the
  reference dataset, 4.5 s at 1/8 — some 36 s at full scale (*arithmetic*).
* **Loading takes about three times as long as saving** (6 s and 12 s): the heap grows as it loads,
  and the collector's mutator assists hold it (research R-7).
* **A killed save leaves `temp-<pid>.kesh`** beside the snapshot, never loaded.
* **`BGSAVE`'s `fork()` holds every command** while the kernel copies the page tables: 11 ms median at
  1/64 of the reference dataset, 48–85 ms at 1/8 (B-25).
* **A `BGSAVE` under load needs about 1.4 × `used_memory` beyond what the server holds** — 4.6 × in
  all at 1/8, against the chart's 3.3 ×. The parent's collector writes into every live object during
  the save, so copy-on-write copies nearly its whole heap (research R-6). In a pod at its limit, the
  OOM killer takes the larger process. Left so by the owner's decision (B-29): nothing in kesh issues
  a `BGSAVE`, and the chart's limit does not budget one — its comment and `services/deploy.md` say so.

---

Why it is built this way, and what is still a hypothesis: [research-architecture](../research/research-architecture.md).
