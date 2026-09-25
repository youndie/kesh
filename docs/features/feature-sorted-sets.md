---
id: feature-sorted-sets
title: Sorted sets
type: feature
status: active
owner: unassigned
involved_services: [store, server, conformance]
client_entries: []
api: [endpoint-sorted-sets]
tags: [data-types]
---

# Sorted sets

## 1. Overview

Leaderboards, time-ordered indexes, priority queues. In the reference dataset, 2 000 boards of
1 000–100 000 members — on the order of 10–20 M members in total, so the object count per member is
a design input, not only the complexity: 3.5 objects a member on average (research D-23).

## 2. Business rules

* `ZADD` supports `NX`, `XX`, `GT`, `LT`, `CH` and `INCR`, with Redis's incompatibility errors
  (`XX and NX …`, `GT, LT, and/or NX …`, `INCR option supports a single increment-element pair`);
  `ZADD … INCR` and `ZINCRBY` answer the new score, or null when a condition stopped the write.
* Also `ZREM`, `ZSCORE`, `ZMSCORE`, `ZINCRBY`, `ZCARD`, `ZRANK` and `ZREVRANK` (with 7.2's
  `WITHSCORE`), `ZCOUNT`, `ZRANGE` (with `BYSCORE`, `BYLEX`, `REV`, `LIMIT` and `WITHSCORES`),
  `ZREVRANGE`, `ZREMRANGEBYSCORE`, `ZREMRANGEBYRANK`, `ZPOPMIN`, `ZPOPMAX`. `ZSCAN` since B-10.
* Ordering is by score, then by member bytes, unsigned.
* Rank and range queries are logarithmic in the set's size.
* An empty sorted set is deleted — by `ZREM`, a `ZREMRANGE…` or a pop.
* **Numbers, as Redis reads and prints them.** A score is read as `string2d` reads it — no leading
  space, no overflow to infinity, no underflow to zero, `inf` taken, `nan` refused. A range bound is
  read as bare `strtod` reads it — leading space skipped, an empty string is `0`, `1e400` is `inf`.
  A score is printed as `d2string` prints it: integers as integers, `1.5e-7`, `1e+20`, `-0`.
* In `LIMIT offset count`, a negative offset selects nothing and a negative count everything.
* Under `noeviction`, a write that would exceed `maxmemory` answers `-OOM command not allowed when used memory > 'maxmemory'.` (B-11) — any command Redis flags `denyoom`; reads and deletes still run. **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/command/CommandDispatcherTest.kt::a full dataset refuses writes under noeviction and still reads and deletes`.

## 4. Code anchors

| Service | Code |
|---|---|
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/zsets/SkipList.kt` — Redis's skiplist with spans |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/zsets/ZSetValue.kt` — the two encodings, by rank |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/SortedSetCommands.kt` — every command, following `t_zset.c` |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/RedisFloat.kt` — `string2d`, `strtod`, `d2string` |
| conformance | `conformance/scripts/zsets/` — 250 lines: commands, options, bounds, printed scores, both encodings |

## 5. Scenarios

Built in B-09. Beside these, the sorted set script compares 250 replies with Redis 7.2, all agreeing,
every one exact.

### Scenario: Leaderboard
* **Given:** 1 000 000 members in `board:2026-09`
* **When:** `ZREVRANGE board:2026-09 0 9 WITHSCORES`
* **Then:** the top ten come back in descending score
* **And:** `ZREVRANK` of any sampled member matches the oracle
* **Automated:** `store/src/linuxX64Test/kotlin/io/github/youndie/kesh/store/SortedSetScaleTest.kt::a million-member board answers its top ten and ranks` — against the board's arithmetic, not the oracle; ranks against the oracle on a 300-member skiplist in `conformance/scripts/zsets/commands.redis`

### Scenario: Score ties
* **Given:** members `b` and `a` with equal scores
* **Then:** `ZRANGE` returns `a` before `b`
* **Automated:** `store/src/commonTest/kotlin/io/github/youndie/kesh/store/SortedSetCommandsTest.kt::score ties are ordered by member bytes`

### Scenario: Rank stays logarithmic
* **Given:** a 1 k-member and a 1 M-member sorted set
* **When:** `ZRANK` is timed on each
* **Then:** the 1 M-member time is within 10× the 1 k-member time
* **Automated:** `store/src/linuxX64Test/kotlin/io/github/youndie/kesh/store/SortedSetScaleTest.kt::rank on a million members takes within ten times its time on a thousand` — 4.2× on the build machine

## 6. Out of scope

* `ZUNIONSTORE`, `ZINTERSTORE`, `ZRANGESTORE`, `ZRANDMEMBER`, blocking pops — not in the brief's §6;
  nor `ZRANGEBYSCORE`, `ZRANGEBYLEX`, `ZREVRANGEBYSCORE`, `ZLEXCOUNT`, `ZREMRANGEBYLEX`, which
  `ZRANGE`'s options replace.

## 7. Quirks

* **`ZREVRANGE` is deprecated in Redis since 6.2** in favour of `ZRANGE … REV`, and the reference load
  uses it anyway; it is served, and conformance covers both forms.
* **Hexadecimal numbers** (`0x10`), which Redis's `strtod` takes as a score or a bound, are refused —
  as `INCRBYFLOAT`'s are ([feature-strings](feature-strings.md) quirks).
* **Redis finds a score's digits with grisu2**, which on rare values is not the shortest; kesh prints
  the shortest. Every score in the conformance script agrees; a value where grisu2 falls back would
  print differently, equally exact.
* **A packed set answers `0` for a score of `-0`, a skiplist `-0`** — Redis's listpack stores an
  integral score as an integer. kesh does the same, on purpose.

---

Why it is built this way, and what is still a hypothesis: [research-architecture](../research/research-architecture.md).
