---
id: snapshot
title: snapshot — the snapshot format, SAVE, load at startup
type: service
status: active
module: snapshot
tech_stack: [Kotlin, common source set, jvm and linuxX64 targets]
owner: unassigned
depends_on: [store]
publishes: []
---

# snapshot

## 1. Responsibility

Writes the whole dataset to a file, consistent as of the moment the save started, and loads it at
startup. Owns kesh's snapshot format (B-14).

**Deliberately does not:** read or write Redis's RDB or AOF (brief §2), append logs, or save in the
background — `BGSAVE` is not in v1 (research R-6, D-24).

## 2. API contracts

* `Snapshot.write(db, sink)` and `Snapshot.read(db, source)` over a byte sink and source: the format
  is common Kotlin, tested on the JVM and on linuxX64. The file itself is the server's
  (`SnapshotFile`).
* Commands through `server`: `SAVE`, `LASTSAVE` (built); `SHUTDOWN SAVE` and saving on stop are
  B-16's.
* The format is private to kesh and versioned in its header: `KESHSNAP`, version 1.

## 2a. Code anchors

| File | What is there |
|---|---|
| `snapshot/src/commonMain/kotlin/io/github/youndie/kesh/snapshot/Snapshot.kt` | the format: header, one record per key, the end with a count and a CRC-32 |
| `snapshot/src/commonMain/kotlin/io/github/youndie/kesh/snapshot/Crc32.kt` | CRC-32, zlib's polynomial |
| `snapshot/src/commonTest/kotlin/io/github/youndie/kesh/snapshot/SnapshotTest.kt` | every kind round-trips; cut and damaged files are refused |
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/persistence/SnapshotFile.kt` | the file: temporary name, `fsync`, rename, `fsync` of the directory; reading at startup |
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/persistence/Persistence.kt` | `SAVE`, `LASTSAVE` |
| `bench/snapshot/check.py` | the scenarios through the running server, and R-5's numbers |
| `bench/src/nativeMain/kotlin/io/github/youndie/kesh/bench/fork/Main.kt` | the fork probe behind R-6's decision |

## 3. How it is built

* **One file, streamed**: a record per key — type, key, absolute expiry or -1, value — and at the end
  the number of records and a CRC-32 of everything before it. A file cut anywhere or with one byte
  changed is refused, whole: nothing read from it is served (`SnapshotTest`).
* **Written beside, renamed into place** — a temporary file in the same directory, `fsync`, `rename`
  over the snapshot, `fsync` of the directory, Redis's order. A process killed during `SAVE` leaves
  the previous snapshot (B-14's torn-save scenario, run with `SIGKILL`).
* **Expired keys are neither written nor loaded**; expiries are absolute milliseconds.
* **Loaded before the listener exists** (research D-24): until the load is done a client is refused at
  connect, as the brief says, rather than answered `-LOADING` as Redis answers. A snapshot that
  cannot be read stops the start with one line and exit status 1.
* **`SAVE` holds the store thread** for its whole write, as Redis's `SAVE` holds its event loop.
  Measured (research R-5): 2.3 s for 271 MB at 1/16 of the reference dataset, 4.5 s for 542 MB at
  1/8; loading 6 s and 12 s.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Module | [store](store.md) | the keyspace to walk, the values to rebuild |
| Storage | a directory (`KESH_DIR`), a persistent volume in the chart | the snapshot file |

## 7. Configuration

`KESH_DIR` — the snapshot's directory (default `.`); `KESH_DBFILENAME` — its name (default
`dump.kesh`). Saving on stop is B-16's.

## 8. Quirks

* **A save killed mid-way leaves its temporary file** (`temp-<pid>.kesh`) beside the snapshot, as
  Redis leaves its `temp-<pid>.rdb`. It is never loaded; removing it is the operator's.
* **Loading stalls on the collector's mutator assists** while the heap grows (research R-7): 12 s for
  2 M keys, where writing them took 4.5 s. B-23 decides whether to turn them off.
* **No `BGSAVE`** (research R-6): a forked child of the runtime hangs in its first mutator assist,
  and works once assists are off — a finding, not yet a feature (B-25).
