---
id: endpoint-sets
title: Set commands
type: api_endpoints
status: active
services:
  - store
  - server
contract_source:
  - "kesh:store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/SetCommands.kt"
parent_feature: feature-sets
---

# API: set commands

Membership. Part of [feature-sets](../features/feature-sets.md).

The authority on each command's syntax, reply and error is Redis 7.2, held by the differential
harness ([conformance](../services/conformance.md), `conformance/scripts/sets/`). Error strings were
read from `redis/redis@7.2.5!/src/t_set.c`, `object.c` and `server.c`.

## Commands — all of them

| Command | Arity | Auth tier | In Redis since | Notes |
|---|---|---|---|---|
| `SADD`, `SREM` | -3 | password | 1.0.0; several members 2.4.0 | answer how many changed |
| `SISMEMBER` | 3 | password | 1.0.0 | |
| `SMISMEMBER` | -3 | password | 6.2.0 | a missing key answers zeros |
| `SMEMBERS`, `SCARD` | 2 | password | 1.0.0 | `SMEMBERS` is `sinterCommand` over one key in 7.2 |
| `SPOP`, `SRANDMEMBER` | -2 | password | 1.0.0; count 3.2.0 / 2.6.0 | random: conformance checks membership and count |
| `SINTER`, `SUNION`, `SDIFF` | -2 | password | 1.0.0 | unordered: conformance compares multisets |
| `SSCAN` | — | — | 2.8.0 | *target*, B-10 |

Auth tier `password` means: answered only after `AUTH` when `requirepass` is set, otherwise to anyone.

## Handlers (code anchors)

| Group | Handler |
|---|---|
| Set commands | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/SetCommands.kt` |
| The value | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/sets/SetValue.kt` |

## Errors

| Condition | Reply | Where Redis says it |
|---|---|---|
| wrong type | `-WRONGTYPE Operation against a key holding the wrong kind of value` | `server.c` — `checkType` |
| `SPOP` count not a number, or negative | `-ERR value is out of range, must be positive` | `object.c` — `getPositiveLongFromObjectOrReply` |
| `SRANDMEMBER` count not a number | `-ERR value is not an integer or out of range` | `object.c` — `getLongLongFromObjectOrReply` |
| `SRANDMEMBER` count of `LLONG_MIN` | `-ERR value is out of range, value must between -9223372036854775807 and 9223372036854775807` | `object.c` — `getRangeLongFromObjectOrReply` |
| a third argument to `SPOP` or `SRANDMEMBER` | `-ERR syntax error` | `t_set.c` — `spopCommand`, `srandmemberCommand` |
| over `maxmemory` | `-OOM …` | *target*, B-11 |
