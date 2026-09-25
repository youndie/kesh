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

Built in B-11: `used_memory`, `maxmemory`, `noeviction`. *Target*: the eviction policies (B-12), the
container check (B-26, below).

## 2. Business rules

* `maxmemory` is a byte limit on the dataset as kesh accounts for it, reported as `used_memory` in
  `INFO memory` — an estimate (research D-10), held to a recount by its test. **Resident memory is
  2.4 × `used_memory` idle and 2.8 × at a load's peak** on the reference dataset (B-11's report);
  how that drifts over a day is B-18's.
* Under `noeviction` (the default and, until B-12, the only policy), a command Redis flags `denyoom`
  is refused while `used_memory > maxmemory` with `-OOM command not allowed when used memory >
  'maxmemory'.`; reads, deletes and every other write still run — the same commands as Redis's
  `commands.def`, 23 of kesh's.
* `maxmemory` is set at startup (`KESH_MAXMEMORY`) and at runtime (`CONFIG SET maxmemory`), in
  `memtoull`'s syntax: a number and an optional unit, `k`/`m`/`g` decimal and `kb`/`mb`/`gb` binary.
  0 is no limit.
* Deletion lowers `used_memory` at once; resident memory falls when the collector sweeps (research
  D-15).
* *Target* (B-12): `allkeys-lru`, `volatile-lru`, `allkeys-random`, `volatile-random`,
  `volatile-ttl`, `maxmemory-samples`.
* *Target* (B-26): **a `maxmemory` that, with the measured peak ratio, exceeds the container's
  memory budget is refused** — at startup and on `CONFIG SET`, naming both numbers. Waits on a kore
  release that has `containerMemoryBudget()`.

## 3. The commands this feature adds

| Command | Reply | Notes |
|---|---|---|
| `CONFIG GET maxmemory` | the pair, under the name as the client wrote it first | only exact names kesh knows; others give nothing |
| `CONFIG SET maxmemory <value> [...]` | `+OK` | `config.c`'s errors: `Unknown option or number of arguments for CONFIG SET - '<name>'`, `CONFIG SET failed (possibly related to argument 'maxmemory') - argument must be a memory value`, `… - duplicate parameter`, `syntax error` |
| `INFO [memory]` | `used_memory`, `used_memory_human`, `maxmemory`, `maxmemory_human`, `maxmemory_policy` | the rest of `INFO` is B-15's |

The rest of `CONFIG` and `INFO` is drafted in *endpoint-server*, with B-15.

## 4. Code anchors

| Service | Code |
|---|---|
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/memory/MemoryModel.kt` — what an entry and each value kind are estimated to cost |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/Db.kt` — the running total, settled after each write command |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/StoreCommand.kt` — `write` and `denyoom`, from Redis's table |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/config/MemoryConfig.kt` — `CONFIG GET`/`SET maxmemory`, `INFO memory`, `memtoull` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/command/CommandDispatcher.kt` — the `denyoom` refusal |
| conformance | `conformance/scripts/memory/` — which commands Redis refuses when full, and `CONFIG`'s replies |
| bench | `bench/memory/ratio.sh` — resident memory against `used_memory`; `bench/reports/b-11/` |

## 5. Scenarios

### Scenario: Refuse when full
* **Given:** `maxmemory` set and `noeviction`
* **When:** a client writes until the limit is reached
* **Then:** the next `SET` answers `-OOM command not allowed when used memory > 'maxmemory'.`
* **And:** `GET` of an existing key still works, and `DEL` still works
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/command/CommandDispatcherTest.kt::a full dataset refuses writes under noeviction and still reads and deletes` — at 20 KiB; against Redis 7.2 at `maxmemory 1` in `conformance/scripts/memory/noeviction.redis`, every refused and every allowed command alike

### Scenario: Evict when full
*Target*, B-12.
* **Given:** `allkeys-lru` and a client that keeps writing new keys
* **Then:** `used_memory` stays under `maxmemory` plus one sampling batch
* **And:** `evicted_keys` in `INFO stats` grows

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

* `allkeys-lfu`, `volatile-lfu` — not in the brief.

## 7. Quirks

* **`used_memory` is an estimate, not a measurement** (research D-10): `MemoryModel`'s object sizes,
  not the heap's. Resident memory stands 2.4–2.8 times above it.
* **A read never changes `used_memory`**, except by deleting an expired key it finds.

---

Why it is built this way, and what is still a hypothesis: [research-architecture](../research/research-architecture.md).
