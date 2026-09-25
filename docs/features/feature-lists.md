---
id: feature-lists
title: Lists
type: feature
status: active
owner: unassigned
involved_services: [store, server, conformance]
client_entries: []
api: [endpoint-lists]
tags: [data-types]
---

# Lists

## 1. Overview

Queues and recent-items lists. In the reference dataset, 300 k feeds of 20–200 item ids, written
with `LPUSH` plus `LTRIM`. Stored as chunks of packed items, so that the collector sees chunks, not
items (research D-21).

## 2. Business rules

* `LPUSH`, `RPUSH` (several items, answering the new length), `LPOP` and `RPOP` (with an optional
  count), `LRANGE`, `LLEN`, `LINDEX`, `LSET`, `LTRIM`, `LREM`.
* `LPUSH k a b c` pushes one item at a time, so the list reads `c b a`.
* Negative indexes count from the tail; ranges are clamped to the list, and a range that selects
  nothing is an empty reply (`LRANGE`) or an emptied list (`LTRIM`).
* An empty list is deleted — by a pop, `LTRIM` or `LREM`.
* `LSET` on a missing key answers `-ERR no such key`; out of range, `-ERR index out of range`.
* A pop's count that is not a number or is negative answers `-ERR value is out of range, must be
  positive`; a count of 0 answers an empty array, and a missing key with a count the null array.
* Whether a number or the key is checked first follows Redis command by command — `LINDEX` and
  `LSET` look at the key first, `LRANGE`, `LTRIM`, `LREM` and the pops read their numbers first.
* *Target* (B-11): a write that would exceed `maxmemory` under `noeviction` answers `-OOM …`.

## 4. Code anchors

| Service | Code |
|---|---|
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/lists/ListValue.kt` — the chunks |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/ListCommands.kt` — every list command, following `t_list.c` |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/packed/Packed.kt` — the packed layout, shared with hashes |
| conformance | `conformance/scripts/lists/` — the commands, their refusals, and a 2 000-item list cut across chunks |

## 5. Scenarios

Built in B-07. Beside these, the list script compares 142 replies with Redis 7.2, all agreeing.

### Scenario: Recent items
* **Given:** `LPUSH feed:7 a b c`
* **When:** `LTRIM feed:7 0 1`
* **Then:** `LRANGE feed:7 0 -1` returns `c`, `b` in that order
* **Automated:** `store/src/commonTest/kotlin/io/github/youndie/kesh/store/ListCommandsTest.kt::a feed keeps its most recent items`

### Scenario: Empty list is deleted
* **Given:** `RPUSH q x`
* **When:** `RPOP q`
* **Then:** `EXISTS q` replies `:0`
* **Automated:** `store/src/commonTest/kotlin/io/github/youndie/kesh/store/ListCommandsTest.kt::the key goes with its last item`

## 6. Out of scope

* Blocking pops (`BLPOP` and friends), `LMOVE`, `LPOS` (brief §2 and §6), and `LPUSHX`, `RPUSHX`,
  `LINSERT`, `LMPOP`, `RPOPLPUSH`, which the brief does not list.

## 7. Quirks

* **A command that throws closes its connection**, not the server: the server logs
  `kesh: connection failed: …` (`KeshServer`'s exception handler) and the client sees the stream end
  inside a reply. Seen while mutating the chunk walk; no path is known to throw with the code as it is.

---

Why it is built this way, and what is still a hypothesis: [research-architecture](../research/research-architecture.md).
