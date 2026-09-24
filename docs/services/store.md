---
id: store
title: store — the keyspace, its value kinds, expiry, accounting, eviction
type: service
status: active
module: store
tech_stack: [Kotlin, common source set, jvm and linuxX64 targets]
owner: unassigned
depends_on: [resp]
publishes: []
---

# store

## 1. Responsibility

Owns all data: the keyspace, the value kinds, key expiry, and — later — memory accounting and
eviction. Every data command's semantics lives here; `server` checks existence, arity and
authentication and dispatches to it.

**Built (B-05):** kesh's own hash table (research D-12), strings, the keyspace commands except `SCAN`,
and lazy expiry. ***Target*:** hashes (B-06), lists (B-07), sets (B-08), sorted sets (B-09), `SCAN`
(B-10), memory accounting and `maxmemory` (B-11), eviction (B-12), active expiry (B-13).

**Deliberately does not:** do I/O, parse or write the wire, persist anything (that is `snapshot`), or
free memory — under a tracing collector nothing does (research D-15).

## 2. API contracts

* The command groups it implements: [endpoint-strings](../api/endpoint-strings.md) and
  [endpoint-keyspace](../api/endpoint-keyspace.md). Hashes, lists, sets and sorted sets are drafted
  in the *docs/layer-drafts* branch.
* **A command** is a `StoreCommand`: Redis's name and arity, and a handler over the `Db` returning a
  RESP reply. Each handler follows its Redis function check by check — the addresses are in the KDoc.
* **Threading:** single-threaded. Every call happens on the store thread (research D-14); nothing in
  this module is thread-safe, on purpose.
* **Time:** one instant per command, `Db.now`, set by the dispatcher before the handler runs — Redis's
  `commandTimeSnapshot`.

## 2a. Code anchors

| File | What is there |
|---|---|
| `store/src/commonMain/kotlin/io/github/youndie/kesh/store/keyspace/Keyspace.kt` | the table: bucket chains, incremental rehash, content hashing with a per-process seed |
| `store/src/commonMain/kotlin/io/github/youndie/kesh/store/Db.kt` | lazy expiry against the command's instant; `set` with and without keeping the TTL |
| `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/StringCommands.kt` | the twenty string commands (`t_string.c`) |
| `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/KeyCommands.kt` | the keyspace commands (`db.c`, `expire.c`) |
| `store/src/commonMain/kotlin/io/github/youndie/kesh/store/Glob.kt` | `stringmatchlen`, for `KEYS` and later `SCAN … MATCH` |
| `store/src/commonMain/kotlin/io/github/youndie/kesh/store/RedisFloat.kt` | float parsing and printing — and its known divergence |
| `store/src/commonTest/kotlin/io/github/youndie/kesh/store/` | tests on the JVM and linuxX64, time moved by hand |

## 3. How it is built

* **Its own hash table, not `HashMap`** (research §1.3, D-12). Two tables during a resize; every
  operation moves at most one bucket (and visits at most ten empty ones), so no command pays for the
  whole table. `KeyspaceTest` grows a table to a million keys and asserts the bound on every step.
  The power-of-two table is also what `SCAN`'s guarantee needs (B-10).
* **Keys hash by content, with a seed.** A `ByteArray` compares by identity, so the table hashes its
  bytes — FNV-1a finished like MurmurHash3's `fmix32` — with a seed the server draws per process, so
  keys chosen from outside cannot be made to collide on purpose.
* **Lazy expiry exactly as Redis's.** A key is expired when `now > expireAt` — strictly — and is
  deleted by whatever looks it up first (`Db.lookup`). `DBSIZE` counts expired keys not yet looked
  up, as Redis's `dictSize` does; `KEYS` skips them without deleting them, as Redis's does.
* **The expiry lives on the entry**, not in a second table. Active expiry (B-13) needs an index of
  the keys that have one, and adds it then.
* **Strings are `ByteArray` values; the type of a value is its class.** A command that finds another
  class answers `WRONGTYPE` (`stringOf`); the other kinds arrive with B-06 to B-09.
* **Packed where it will count** (research D-3, B-19): the collection kinds will be packed into
  `ByteArray`s where small — for memory, not for the collector's pause, which follows the allocator's
  pages (research §1.2, correction).

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Module | [resp](resp.md) | the reply type, and `string2ll` |

## 5. Infrastructure and deploy

A module of this build; not published.

## 7. Configuration

`proto-max-bulk-len` bounds `APPEND` and `SETRANGE` (`StringCommands.maxStringLength`). `maxmemory`,
its policy and samples arrive with B-11 and B-12.

## 8. Quirks

* **`INCRBYFLOAT` computes in `Double`**, where Redis on x86-64 uses 80-bit `long double`. They agree
  wherever the 64-bit result is already the expected decimal (`10.5 + 0.1` → `10.6`) and differ where
  x86's extra bits round an error away (`0.1 + 0.2`: Redis `0.3`, kesh `0.30000000000000004`). Redis
  itself answers differently on platforms whose `long double` is a `double`. Hexadecimal floats, which
  Redis's `strtold` accepts, are refused.
* **The table never shrinks** except on `FLUSHALL`. Redis shrinks a table below 10 % full from its
  cron; kesh has no cron yet (B-13 brings the first periodic work).
* **`used_memory` falls when a key is deleted; resident memory falls only after a GC cycle** — and
  `used_memory` itself is B-11's.
* **`DEL` and `UNLINK` are the same operation**, as are `FLUSHALL` with and without `ASYNC`
  (research D-15).
