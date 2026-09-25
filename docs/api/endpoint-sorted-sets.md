---
id: endpoint-sorted-sets
title: Sorted set commands
type: api_endpoints
status: active
services:
  - store
  - server
contract_source:
  - "kesh:store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/SortedSetCommands.kt"
parent_feature: feature-sorted-sets
---

# API: sorted set commands

Leaderboards, time-ordered indexes, priority queues. Part of [feature-sorted-sets](../features/feature-sorted-sets.md).

The authority on each command's syntax, reply and error is Redis 7.2, held by the differential
harness ([conformance](../services/conformance.md), `conformance/scripts/zsets/`). Error strings were
read from `redis/redis@7.2.5!/src/t_zset.c`, `object.c` and `server.c`; arities from `commands.def`.

## Commands — all of them

| Command | Arity | Auth tier | In Redis since | Notes |
|---|---|---|---|---|
| `ZADD [NX\|XX] [GT\|LT] [CH] [INCR]` | -4 | password | 1.2.0; GT/LT 6.2.0 | |
| `ZINCRBY` | 4 | password | 1.2.0 | `ZADD INCR` with its own arity |
| `ZREM` | -3 | password | 1.2.0 | |
| `ZCARD` | 2 | password | 1.2.0 | |
| `ZSCORE` | 3 | password | 1.2.0 | |
| `ZMSCORE` | -3 | password | 6.2.0 | |
| `ZRANK`, `ZREVRANK [WITHSCORE]` | -3 | password | 2.0.0; `WITHSCORE` 7.2.0 | |
| `ZCOUNT` | 4 | password | 2.0.0 | |
| `ZRANGE [BYSCORE\|BYLEX] [REV] [LIMIT] [WITHSCORES]` | -4 | password | 1.2.0; options 6.2.0 | |
| `ZREVRANGE [WITHSCORES]` | -4 | password | 1.2.0 | deprecated in Redis, served; `REV`, `BYSCORE`, `BYLEX` are syntax errors here |
| `ZREMRANGEBYSCORE`, `ZREMRANGEBYRANK` | 4 | password | 1.2.0 / 2.0.0 | |
| `ZPOPMIN`, `ZPOPMAX [count]` | -2 | password | 5.0.0 | a missing key is an empty array |
| `ZSCAN key cursor [MATCH] [COUNT]` | -3 | password | 2.8.0 | a packed collection comes back whole with cursor 0; `ScanCommands.kt` (B-10) |

Auth tier `password` means: answered only after `AUTH` when `requirepass` is set, otherwise to anyone.

## Handlers (code anchors)

| Group | Handler |
|---|---|
| Sorted set commands | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/SortedSetCommands.kt` |
| The value | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/zsets/ZSetValue.kt` |

## Errors

| Condition | Reply | Where Redis says it |
|---|---|---|
| wrong type | `-WRONGTYPE Operation against a key holding the wrong kind of value` | `server.c` — `checkType` |
| odd or no score–member pairs to `ZADD` | `-ERR syntax error` | `zaddGenericCommand` |
| `XX` with `NX` | `-ERR XX and NX options at the same time are not compatible` | `zaddGenericCommand` |
| `GT`/`LT` with `NX`, or both | `-ERR GT, LT, and/or NX options at the same time are not compatible` | `zaddGenericCommand` |
| `INCR` with several pairs | `-ERR INCR option supports a single increment-element pair` | `zaddGenericCommand` |
| a score not a float | `-ERR value is not a valid float` | `object.c` — `getDoubleFromObjectOrReply` |
| `INCR` to NaN (`inf` + `-inf`) | `-ERR resulting score is not a number (NaN)` | `zaddGenericCommand` |
| bad score bound | `-ERR min or max is not a float` | `zslParseRange` callers |
| bad lexical bound | `-ERR min or max not valid string range item` | `zslParseLexRange` callers |
| `LIMIT` without `BYSCORE`/`BYLEX` | `-ERR syntax error, LIMIT is only supported in combination with either BYSCORE or BYLEX` | `zrangeGenericCommand` |
| `WITHSCORES` with `BYLEX` | `-ERR syntax error, WITHSCORES not supported in combination with BYLEX` | `zrangeGenericCommand` |
| an unknown `ZRANGE` option, or a third argument to a pop | `-ERR syntax error` | `zrangeGenericCommand`, `zpopMinMaxCommand` |
| a pop's count not a number, or negative | `-ERR value is out of range, must be positive` | `object.c` — `getPositiveLongFromObjectOrReply` |
| a rank, index or `LIMIT` not an integer | `-ERR value is not an integer or out of range` | `object.c` — `getLongLongFromObjectOrReply` |
| `ZRANK` with more than one option | `-ERR wrong number of arguments for 'zrank' command` | `zrankGenericCommand` |
| over `maxmemory` under `noeviction` | `-OOM command not allowed when used memory > 'maxmemory'.` | `redis/redis@7.2!/src/server.c` `createSharedObjects` (`shared.oomerr`); built in B-11 |
