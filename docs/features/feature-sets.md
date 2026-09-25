---
id: feature-sets
title: Sets
type: feature
status: active
owner: unassigned
involved_services: [store, server, conformance]
client_entries: []
api: [endpoint-sets]
tags: [data-types]
---

# Sets

## 1. Overview

Membership: tags, followers, seen ids. In the reference dataset, 500 k small sets of 3–15 short
members; a small set is one packed `ByteArray`, as a small hash is (research D-22).

## 2. Business rules

* `SADD`, `SREM`, `SISMEMBER`, `SMISMEMBER`, `SMEMBERS`, `SCARD`, `SRANDMEMBER`, `SPOP`, `SINTER`,
  `SUNION`, `SDIFF`. `SSCAN` since B-10.
* An empty set is deleted — by `SREM` or `SPOP`; `SREM` stops at the member that emptied it.
* Replies of several members come in no promised order, and kesh's differs from Redis's (Redis keeps
  small integer sets sorted; kesh does not). Conformance compares them as multisets.
* `SPOP` and `SRANDMEMBER` return members at random. `SRANDMEMBER` with a positive count never
  repeats a member and stops at the whole set; with a negative one it repeats freely and is always
  that long. `SPOP` with a count reaching the set's size takes the whole set, and the key.
* A third argument to `SPOP` or `SRANDMEMBER` is `-ERR syntax error`, not an arity error.
* `SINTER`, `SUNION` and `SDIFF` check the type of every key before answering: a wrong-type key
  after a missing one still answers `WRONGTYPE`. A missing key is an empty set.
* Under `noeviction`, a write that would exceed `maxmemory` answers `-OOM command not allowed when used memory > 'maxmemory'.` (B-11) — any command Redis flags `denyoom`; reads and deletes still run. **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/command/CommandDispatcherTest.kt::a full dataset refuses writes under noeviction and still reads and deletes`.

## 4. Code anchors

| Service | Code |
|---|---|
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/sets/SetValue.kt` — the two encodings |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/SetCommands.kt` — every set command, following `t_set.c` |
| conformance | `conformance/scripts/sets/` — the commands, the random ones by population, and the encoding boundary |

## 5. Scenarios

Built in B-08. Beside these, the set script compares 133 replies with Redis 7.2, all agreeing.

### Scenario: Tags
* **Given:** `SADD post:9:tags kotlin native`
* **Then:** `SISMEMBER post:9:tags kotlin` replies `:1`
* **And:** `SCARD post:9:tags` replies `:2`
* **Automated:** `store/src/commonTest/kotlin/io/github/youndie/kesh/store/SetCommandsTest.kt::tags are members and counted`

### Scenario: Random member stays a member
* **Given:** a set of 10 members
* **When:** `SRANDMEMBER s 3`
* **Then:** three distinct replies, each a member of the set
* **Automated:** `store/src/commonTest/kotlin/io/github/youndie/kesh/store/SetCommandsTest.kt::a random member stays a member`; against Redis, `[random …]` lines in `conformance/scripts/sets/commands.redis`

## 6. Out of scope

* `SMOVE`, `SINTERCARD`, the `*STORE` variants — not in the brief's §6.

## 7. Quirks

* **A huge negative `SRANDMEMBER` count** builds its whole reply before sending it, where Redis
  streams one; a count beyond memory closes the connection (feature-lists quirks).

---

Why it is built this way, and what is still a hypothesis: [research-architecture](../research/research-architecture.md).
