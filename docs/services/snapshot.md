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

**Deliberately does not:** read or write Redis's RDB or AOF (brief §2), or append logs. `BGSAVE`
(B-25) is the server's: a forked child calls the same `Snapshot.write`.

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
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/persistence/Persistence.kt` | `SAVE`, `BGSAVE` (the fork, the child, the reaping), `LASTSAVE` |
| `store/src/commonMain/kotlin/io/github/youndie/kesh/store/ByteSlice.kt` | how the writer reads a collection without copying it |
| `bench/snapshot/check.py` | the scenarios through the running server, and R-5's numbers |
| `bench/src/nativeMain/kotlin/io/github/youndie/kesh/bench/fork/Main.kt` | the fork probe behind R-6's first decision |
| `bench/fork/bgsave.sh`, `bench/fork/bgsave.py` | `BGSAVE` again and again under load, and the memory of both processes (B-25) |

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
* **The writer allocates nothing per element** (B-25): hashes, sets, lists and sorted sets are written
  from where the store keeps them (`ByteSlice`, `ScoredSlice`), not copied out first. A background
  save's child has no collector, so every copy would stay in its heap until it exited (research R-6).
  Keep it so: a `forEach` that hands out fresh arrays, used here, grows the child again.
* **A killed background save's temporary file is removed by the server** (`temp-<child pid>.kesh`),
  as Redis's `rdbRemoveTempFile`; a save killed with the whole process still leaves its own.
