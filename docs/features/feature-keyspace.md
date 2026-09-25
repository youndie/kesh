---
id: feature-keyspace
title: Keys, expiry and iteration
type: feature
status: active
owner: unassigned
involved_services: [store, server, conformance]
client_entries: []
api: [endpoint-keyspace]
tags: [keyspace]
---

# Keys, expiry and iteration

## 1. Overview

Commands that act on keys whatever their type: delete, test, expire, rename, iterate. The keyspace
itself is kesh's own hash table with incremental rehashing, not the stdlib map (research §1.3,
D-12) — it is what makes growth free of stalls and `SCAN` complete.

## 2. Business rules

Built in B-05, `SCAN` in B-10, `used_memory` in B-11: everything but active expiry (B-13).

* **`DEL`, `UNLINK` and `FLUSHALL`/`FLUSHDB` with or without `ASYNC` behave the same** (research
  D-15, amending the brief): the keys are gone from the keyspace — and, from B-11, from `used_memory`
  — before the reply; the heap returns the memory at the next collection, for all of them. Under a
  tracing collector there is no synchronous free to distinguish them by.
* A key whose expiry has passed is never returned, whether or not it has been reclaimed yet: it is
  expired when `now > expireAt`, strictly, against one instant per command, and deleted by whatever
  looks it up first. `DBSIZE` counts expired keys nothing has looked up yet, as Redis's does.
* `EXPIRE` with a time at or before now deletes the key and answers `1`.
* Expiry is **lazy** (checked on access) and — *target*, B-13 — **active**: ten times a second a cycle samples 20 keys
  with an expiry, deletes the expired ones, and repeats while more than **10 %** of the sample was
  expired, within **25 % of CPU** time (research §1.5, D-11 as corrected — the brief's "25 %" was the
  CPU budget, not the repeat threshold).
* `SCAN` is a cursor iteration with Redis's guarantee: every key present for the whole iteration is
  returned at least once, across resizes — Redis's reverse-binary cursor over the power-of-two table
  (`Keyspace.scan`, a port of `dictScan`). `COUNT`, `MATCH` and `TYPE` are supported; `HSCAN`,
  `SSCAN` and `ZSCAN` walk a collection's table the same way.
* `KEYS pattern` is supported and documented as unsuitable for production, as in Redis.
* `TTL` answers `-1` for a key without expiry and `-2` for a missing key.

## 4. Code anchors

| Service | Code |
|---|---|
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/keyspace/Keyspace.kt` — the table, incremental rehash |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/Db.kt` — lazy expiry |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/KeyCommands.kt` — the commands |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/ScanCommands.kt` — `SCAN`, `HSCAN`, `SSCAN`, `ZSCAN` |
| conformance | `conformance/scripts/scan/` — full iterations compared with Redis 7.2 as unions |
| conformance | `conformance/scripts/keyspace/` — every command and refusal here against Redis 7.2 |

The active expiry cycle (B-13) will live beside these.

## 5. Scenarios

Built in B-05: *TTL*, *Growth without a stall*; in B-10: *Scan completeness*; in B-11: *DEL and
UNLINK account the same*. *Target*: *Active expiry* (B-13).

### Scenario: TTL
* **Given:** `SET k v EX 10`
* **When:** `TTL k`
* **Then:** the reply is an integer from 1 to 10
* **And:** after `PERSIST k`, `TTL k` is `:-1`; for a missing key it is `:-2`
* **Automated:** `store/src/commonTest/kotlin/io/github/youndie/kesh/store/CommandsTest.kt::TTL is -2 for a missing key and -1 without expiry and rounds to the nearest second`

### Scenario: Scan completeness
* **Given:** 1 000 000 keys, with keys being added and deleted concurrently
* **When:** a client iterates `SCAN 0 COUNT 1000` until the cursor returns to 0, across at least one resize
* **Then:** every key present for the whole iteration is seen at least once
* **Automated:** `store/src/linuxX64Test/kotlin/io/github/youndie/kesh/store/ScanScaleTest.kt::a million-key scan sees every stable key while keys come and go across a resize` — on the table, in steps of about a hundred entries, 150 000 keys added and 50 000 deleted in between; the same at 100 000 keys in `ScanTest` on both targets

### Scenario: Active expiry
* **Given:** 100 000 keys set with `PX 50` that are never read again
* **When:** 2 s pass
* **Then:** `DBSIZE` has fallen by at least 99 %

### Scenario: Growth without a stall
* **Given:** an empty keyspace
* **When:** 16 M keys are inserted
* **Then:** no single command moves more than the bounded number of buckets per operation, and the slowest `SET` of the run is reported
* **Automated:** `store/src/commonTest/kotlin/io/github/youndie/kesh/store/KeyspaceTest.kt::growth moves a bounded number of buckets per operation and loses nothing` — to a million keys; the 16 M run against the binary is in B-05's findings

### Scenario: DEL and UNLINK account the same
* **Given:** two identical 1 MB values at `a` and `b`
* **When:** `DEL a` and `UNLINK b`
* **Then:** each lowers `used_memory` by the same amount before its reply
* **Automated:** `store/src/commonTest/kotlin/io/github/youndie/kesh/store/MemoryAccountingTest.kt::DEL and UNLINK lower used_memory by the same amount before they reply`

## 6. Out of scope

* `OBJECT`, `WAIT`, `WATCH`, `MOVE`, `SWAPDB`, `COPY`, `DUMP`/`RESTORE` — not in the brief's §6,
  and nothing in the portfolio uses them (research §1.1).

## 7. Quirks

* **Resident memory does not follow `used_memory` down** after a large delete: the collector
  reclaims later, and a non-moving heap may keep partly used pages (research R-2).
* **Small collections are returned whole by the first `HSCAN`/`SSCAN`/`ZSCAN` call** with cursor 0,
  whatever cursor was asked for, as Redis does for its packed encodings. A set of 129–512 integers
  is whole in Redis (an intset) and walked by cursor in kesh (a table, research D-22): the same
  union, over more calls.
* **`ZSCAN` prints a large sorted set's scores differently from every other command**: `%.17Lg`,
  so `0.1` is `0.10000000000000001` — Redis's `scanCallback` does, and kesh follows it. A packed
  sorted set's scores print as `ZSCORE` prints them, as Redis's listpack stores them.

---

Why it is built this way, and what is still a hypothesis: [research-architecture](../research/research-architecture.md).
