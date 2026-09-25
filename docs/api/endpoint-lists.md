---
id: endpoint-lists
title: List commands
type: api_endpoints
status: active
services:
  - store
  - server
contract_source:
  - "kesh:store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/ListCommands.kt"
parent_feature: feature-lists
---

# API: list commands

Queues and recent-items lists. Part of [feature-lists](../features/feature-lists.md).

The authority on each command's syntax, reply and error is Redis 7.2, held by the differential
harness ([conformance](../services/conformance.md), `conformance/scripts/lists/`). Error strings were
read from `redis/redis@7.2.5!/src/t_list.c`, `object.c` and `server.c`.

## Commands — all of them

| Command | Arity | Auth tier | In Redis since | Notes |
|---|---|---|---|---|
| `LPUSH`, `RPUSH` | -3 | password | 1.0.0; several items 2.4.0 | answers the new length |
| `LPOP`, `RPOP` | -2 | password | 1.0.0; count 6.2.0 | more than one count is the arity error |
| `LLEN` | 2 | password | 1.0.0 | |
| `LINDEX` | 3 | password | 1.0.0 | out of range is null |
| `LSET` | 4 | password | 1.0.0 | |
| `LRANGE`, `LTRIM`, `LREM` | 4 | password | 1.0.0 | |

Auth tier `password` means: answered only after `AUTH` when `requirepass` is set, otherwise to anyone.

## Handlers (code anchors)

| Group | Handler |
|---|---|
| List commands | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/ListCommands.kt` |
| The value | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/lists/ListValue.kt` |

## Errors

| Condition | Reply | Where Redis says it |
|---|---|---|
| wrong type | `-WRONGTYPE Operation against a key holding the wrong kind of value` | `server.c` — `checkType` |
| an index, range bound or `LREM` count not an integer | `-ERR value is not an integer or out of range` | `object.c` — `getLongLongFromObjectOrReply` |
| a pop's count not a number, or negative | `-ERR value is out of range, must be positive` | `object.c` — `getPositiveLongFromObjectOrReply` |
| a pop with more than one count | `-ERR wrong number of arguments for 'lpop' command` | `t_list.c` — `popGenericCommand` |
| `LSET` on a missing key | `-ERR no such key` | `server.c` — `shared.nokeyerr` |
| `LSET` out of range | `-ERR index out of range` | `server.c` — `shared.outofrangeerr` |
| over `maxmemory` | `-OOM …` | *target*, B-11 |
