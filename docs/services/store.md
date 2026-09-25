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

**Built (B-05 to B-07):** kesh's own hash table (research D-12), strings, hashes, lists, the keyspace
commands except `SCAN`, and lazy expiry. ***Target*:** sets (B-08), sorted sets (B-09), `SCAN`
(B-10), memory accounting and `maxmemory` (B-11), eviction (B-12), active expiry (B-13).

**Deliberately does not:** do I/O, parse or write the wire, persist anything (that is `snapshot`), or
free memory — under a tracing collector nothing does (research D-15).

## 2. API contracts

* The command groups it implements: [endpoint-strings](../api/endpoint-strings.md),
  [endpoint-hashes](../api/endpoint-hashes.md), [endpoint-lists](../api/endpoint-lists.md) and
  [endpoint-keyspace](../api/endpoint-keyspace.md). Sets and sorted sets are drafted in the
  *docs/layer-drafts* branch.
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
| `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/HashCommands.kt` | the hash commands (`t_hash.c`) |
| `store/src/commonMain/kotlin/io/github/youndie/kesh/store/hashes/HashValue.kt` | a hash: packed bytes, then a `Keyspace` table (research D-20) |
| `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/ListCommands.kt` | the list commands (`t_list.c`) |
| `store/src/commonMain/kotlin/io/github/youndie/kesh/store/lists/ListValue.kt` | a list: a deque of 8 KiB packed chunks (research D-21) |
| `store/src/commonMain/kotlin/io/github/youndie/kesh/store/packed/Packed.kt` | the length-prefixed layout both packed kinds use |
| `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/KeyCommands.kt` | the keyspace commands (`db.c`, `expire.c`) |
| `store/src/commonMain/kotlin/io/github/youndie/kesh/store/Glob.kt` | `stringmatchlen`, for `KEYS` and later `SCAN … MATCH` |
| `store/src/commonMain/kotlin/io/github/youndie/kesh/store/RedisFloat.kt` | float parsing and printing — and its known divergence |
| `store/src/commonTest/kotlin/io/github/youndie/kesh/store/` | tests on the JVM and linuxX64, time moved by hand |
| `store/src/linuxX64Test/kotlin/io/github/youndie/kesh/store/KeyspaceGrowthTest.kt` | the rehash bound at sixteen million keys, on linuxX64 only (~2 GB) |

## 3. How it is built

* **Its own hash table, not `HashMap`** (research §1.3, D-12). Two tables during a resize; every
  operation moves at most one bucket (and visits at most ten empty ones), so no command pays for the
  whole table. `KeyspaceTest` grows a table to a million keys and asserts the bound on every step;
  `KeyspaceGrowthTest` does the same to sixteen million on linuxX64.
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
  class answers `WRONGTYPE` (`stringOf`, `hashOf`, `listValueOf`); sets and sorted sets arrive with
  B-08 and B-09.
* **Hashes are packed where Redis packs them** (research D-20): one `ByteArray` of length-prefixed
  pairs in insertion order up to 512 fields of at most 64 bytes, then a `Keyspace` table — the
  keyspace's own, so `HSCAN` inherits `SCAN`'s guarantee (B-10). Packed for memory, not for the
  collector's pause, which follows the allocator's pages (research §1.2, correction found in B-19);
  the limits are Redis's so that reply order is Redis's too.
* **Lists are chunks of packed items** (research D-21): a deque of `ByteArray`s of up to 8 KiB each,
  Redis's quicklist in role; an item bigger than a chunk has one to itself. A list's encoding never
  shows in a reply, so the size is Redis's for memory reasons alone.
* **Sets and sorted sets will pack the same way** where small (B-08, B-09).

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Module | [resp](resp.md) | the reply type, and `string2ll` |

## 5. Infrastructure and deploy

A module of this build; not published.

## 7. Configuration

`proto-max-bulk-len` bounds `APPEND` and `SETRANGE` (`StringCommands.maxStringLength`).
`hash-max-listpack-entries` and `hash-max-listpack-value` are `HashValue.maxPackedEntries` and
`maxPackedValue`, and `list-max-listpack-size` is `ListValue.maxChunkBytes` — all at Redis 7.2's
defaults and not settable yet (`CONFIG`, B-15). `maxmemory`,
its policy and samples arrive with B-11 and B-12.

## 8. Quirks

* **`INCRBYFLOAT` computes in `Double`**, where Redis on x86-64 uses 80-bit `long double`. They agree
  wherever the 64-bit result is already the expected decimal (`10.5 + 0.1` → `10.6`) and differ where
  x86's extra bits round an error away (`0.1 + 0.2`: Redis `0.3`, kesh `0.30000000000000004`). Redis
  itself answers differently on platforms whose `long double` is a `double`. Hexadecimal floats, which
  Redis's `strtold` accepts, are refused.
* **A hash never goes back to packed**, however few fields it keeps — as in Redis 7.2.
* **The table never shrinks** except on `FLUSHALL`. Redis shrinks a table below 10 % full from its
  cron; kesh has no cron yet (B-13 brings the first periodic work).
* **`used_memory` falls when a key is deleted; resident memory falls only after a GC cycle** — and
  `used_memory` itself is B-11's.
* **While writes grow the keyspace, commands stall for seconds** — not in the table, which moves one
  bucket per operation at any size, but in the collector: its mutator assists hold every thread
  until a mark finishes, 1.6–4.2 s at 12–27 M live objects (research §1.2, R-7). A 16 M-key load
  with `redis-benchmark` saw 0.1 % of `SET`s over 1.6 s. B-23 decides whether to turn them off.
* **`DEL` and `UNLINK` are the same operation**, as are `FLUSHALL` with and without `ASYNC`
  (research D-15).
