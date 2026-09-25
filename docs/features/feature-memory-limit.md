---
id: feature-memory-limit
title: maxmemory and eviction
type: feature
status: active
owner: unassigned
involved_services: [store, server, bench, conformance]
client_entries: []
api: []
tags: [memory]
---

# maxmemory and eviction

## 1. Overview

The operator sets how much data the instance may hold. When it is full, kesh either evicts by a
policy or refuses writes. On Kotlin/Native this matters more than on Redis: the runtime enforces no
heap ceiling of its own and does not see its container's limit (research §1.2), so kesh's own
accounting is the only thing between a write and an OOM kill.

Built in B-11: `used_memory`, `maxmemory`, `noeviction`; in B-12: the eviction policies. *Target*: the
container check (B-26, below).

## 2. Business rules

* `maxmemory` is a byte limit on the dataset as kesh accounts for it, reported as `used_memory` in
  `INFO memory` — an estimate (research D-10), held to a recount by its test. **Resident memory is
  2.4 × `used_memory` idle and 2.8 × at a load's peak** on the reference dataset (B-11's report);
  how that drifts over a day is B-18's.
* A command Redis flags `denyoom` is refused with `-OOM command not allowed when used memory >
  'maxmemory'.` while `used_memory > maxmemory` **and the policy found nothing left to evict** —
  always under `noeviction` (the default), and under a `volatile-*` policy once no key has an expiry;
  reads, deletes and every other write still run — the same commands as Redis's `commands.def`, 23 of
  kesh's.
* `maxmemory` is set at startup (`KESH_MAXMEMORY`) and at runtime (`CONFIG SET maxmemory`), in
  `memtoull`'s syntax: a number and an optional unit, `k`/`m`/`g` decimal and `kb`/`mb`/`gb` binary.
  0 is no limit.
* Deletion lowers `used_memory` at once; resident memory falls when the collector sweeps (research
  D-15). The index of keys with an expiry (B-13) is counted too: an entry per volatile key.
* **Eviction runs before every command** that passes the arity and authentication checks — reads,
  `PING` and `CONFIG` included — as Redis's `processCommand` runs `performEvictions` (research D-27).
  It deletes keys until the accounting is back under `maxmemory`, so `used_memory` can stand above
  the limit by what one command writes, never more; `evicted_keys` in `INFO stats` counts them.
* The policies, Redis 7.2's: `allkeys-lru` and `volatile-lru` evict the key idle longest, as a pool
  of the 16 best candidates fed by `maxmemory-samples` keys a round (5 by default) finds it;
  `volatile-ttl` the nearest expiry; `allkeys-random` and `volatile-random` any key. `volatile-*` only
  ever take keys with an expiry.
* **An access is any lookup** — `GET`, a write, `EXPIRE` — at one-second resolution (Redis's LRU
  clock); `EXISTS`, `TYPE`, the `TTL` family and `SCAN` look without touching, as in Redis.
* Evicting stops after 500 µs (Redis's default `maxmemory-eviction-tenacity`, not settable in kesh)
  and goes on between commands; so does the eviction `CONFIG SET maxmemory` starts.
* *Target* (B-26): **a `maxmemory` that, with the measured peak ratio, exceeds the container's
  memory budget is refused** — at startup and on `CONFIG SET`, naming both numbers. Waits on a kore
  release that has `containerMemoryBudget()`.

## 3. The commands this feature adds

| Command | Reply | Notes |
|---|---|---|
| `CONFIG GET maxmemory` | the pair, under the name as the client wrote it first | only exact names kesh knows; others give nothing |
| `CONFIG SET maxmemory <value> [...]` | `+OK` | `config.c`'s errors: `Unknown option or number of arguments for CONFIG SET - '<name>'`, `CONFIG SET failed (possibly related to argument 'maxmemory') - argument must be a memory value`, `… - duplicate parameter`, `syntax error` |
| `CONFIG GET maxmemory-policy`, `CONFIG SET maxmemory-policy <policy>` | the policy; `+OK` | any case on `SET`; an unknown name gets `enumConfigSet`'s `argument(s) must be one of the following: …`, listing Redis's eight; the two LFU names are refused in kesh's words (§7) |
| `CONFIG GET maxmemory-samples`, `CONFIG SET maxmemory-samples <n>` | the number; `+OK` | `string2ll`, then `argument must be between 1 and 2147483647 inclusive` |
| `INFO [memory]` | `used_memory`, `used_memory_human`, `maxmemory`, `maxmemory_human`, `maxmemory_policy` | the rest of `INFO` is B-15's |
| `INFO [stats]` | `expired_keys` and the expiry cycle's lines (B-13), `evicted_keys` (B-12) | |

The rest of `CONFIG` and `INFO` is drafted in *endpoint-server*, with B-15.

## 4. Code anchors

| Service | Code |
|---|---|
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/memory/MemoryModel.kt` — what an entry and each value kind are estimated to cost |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/Db.kt` — the running total, settled after each write command |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/StoreCommand.kt` — `write` and `denyoom`, from Redis's table |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/eviction/Eviction.kt` — the policies, the pool, the time limit |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/keyspace/Keyspace.kt` — `sample`, Redis's `dictGetSomeKeys` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/config/MemoryConfig.kt` — `CONFIG GET`/`SET maxmemory`, `INFO memory`, `memtoull` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/command/CommandDispatcher.kt` — eviction before each command, the `denyoom` refusal |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/KeshServer.kt` — the rounds of eviction between commands |
| conformance | `conformance/scripts/memory/` — which commands Redis refuses when full, what each policy leaves at `maxmemory 1`, `evicted_keys`, and `CONFIG`'s replies |
| bench | `bench/memory/ratio.sh` — resident memory against `used_memory`; `bench/reports/b-11/` |

## 5. Scenarios

### Scenario: Refuse when full
* **Given:** `maxmemory` set and `noeviction`
* **When:** a client writes until the limit is reached
* **Then:** the next `SET` answers `-OOM command not allowed when used memory > 'maxmemory'.`
* **And:** `GET` of an existing key still works, and `DEL` still works
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/command/CommandDispatcherTest.kt::a full dataset refuses writes under noeviction and still reads and deletes` — at 20 KiB; against Redis 7.2 at `maxmemory 1` in `conformance/scripts/memory/noeviction.redis`, every refused and every allowed command alike

### Scenario: Evict when full
* **Given:** `allkeys-lru` and a client that keeps writing new keys
* **Then:** no write is refused, and `used_memory` stays under `maxmemory` plus what one command writes
* **And:** `evicted_keys` in `INFO stats` grows
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/command/CommandDispatcherTest.kt::a client that keeps writing under allkeys-lru stays near maxmemory and evicted_keys grows`; which key each policy takes in `store/src/commonTest/kotlin/io/github/youndie/kesh/store/EvictionTest.kt`; every policy against Redis 7.2 at `maxmemory 1` in `conformance/scripts/memory/eviction.redis`

### Scenario: A policy with nothing to take refuses writes
* **Given:** `volatile-lru` and a full dataset whose keys have no expiry
* **When:** a client sends `SET`
* **Then:** it gets the `-OOM` error, nothing is evicted, and `GET` still answers
* **Automated:** `conformance/scripts/memory/eviction.redis`; `store/src/commonTest/kotlin/io/github/youndie/kesh/store/EvictionTest.kt::a volatile policy with nothing volatile fails and so does noeviction`

### Scenario: maxmemory beyond the container
*Target*, B-26.
* **Given:** a container limit of 1 GiB
* **When:** `CONFIG SET maxmemory 2gb`
* **Then:** the command is refused with a message naming the requested value and the budget, and `maxmemory` is unchanged

### Scenario: The reference dataset fits the managed heap
* **Given:** the reference dataset built in-process (B-03's seed) in plain Kotlin objects
* **When:** a write churn shaped like the reference load runs
* **Then:** live heap, resident memory, objects marked and GC pause p50/p99/max are reported, and research D-3 records the verdict against B-20's threshold
* Done by B-19 at a quarter of the dataset — `bench/reports/b-19/README.md`; the full scale is B-17's.

## 6. Out of scope

* `allkeys-lfu`, `volatile-lfu` — not in the brief; refused by `CONFIG SET` (§7).
* `maxmemory-eviction-tenacity` — fixed at Redis's default.

## 7. Quirks

* **`used_memory` is an estimate, not a measurement** (research D-10): `MemoryModel`'s object sizes,
  not the heap's. Resident memory stands 2.4–2.8 times above it.
* **A read never changes `used_memory`**, except by deleting an expired key it finds — or by the
  eviction that runs before it.
* **The LFU policies are refused in kesh's own words**: `CONFIG SET maxmemory-policy allkeys-lfu`
  answers `… - kesh does not implement the LFU policies` where Redis answers `+OK`. Accepting a name
  and evicting by another rule would be the quieter lie.
* **`CONFIG GET` of several names answers in the request's order**; Redis answers in its dictionary's
  order, which varies between processes — compared as `[pairs]`.
* **The rounds between commands start at the periodic work's pace**, up to 100 ms after they are
  needed; Redis arms a timer for the next turn of its event loop. Every command evicts for itself
  meanwhile, so only an idle server is slower to reach the limit.

---

Why it is built this way, and what is still a hypothesis: [research-architecture](../research/research-architecture.md).
