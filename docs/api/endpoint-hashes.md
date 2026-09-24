---
id: endpoint-hashes
title: Hash commands
type: api_endpoints
status: active
services:
  - store
  - server
contract_source:
  - "kesh:store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/HashCommands.kt"
parent_feature: feature-hashes
---

# API: hash commands

Objects with fields. Part of [feature-hashes](../features/feature-hashes.md).

The authority on each command's syntax, reply and error is Redis 7.2, held by the differential
harness ([conformance](../services/conformance.md), `conformance/scripts/hashes/`). Error strings were
read from `redis/redis@7.2.5!/src/t_hash.c` unless the row says otherwise.

## Commands — all of them

| Command | Arity | Auth tier | In Redis since | Notes |
|---|---|---|---|---|
| `HSET` | -4 | password | 2.0.0; multi-pair 4.0.0 | answers the number of new fields |
| `HGET`, `HEXISTS` | 3 | password | 2.0.0 | |
| `HMGET` | -3 | password | 2.0.0 | a missing key answers an array of nulls |
| `HDEL` | -3 | password | 2.0.0; several fields 2.4.0 | the key goes with its last field |
| `HGETALL`, `HKEYS`, `HVALS`, `HLEN` | 2 | password | 2.0.0 | insertion order while packed, table order after (research D-20) |
| `HINCRBY`, `HINCRBYFLOAT` | 4 | password | 2.0.0 / 2.6.0 | |
| `HSCAN` | — | — | 2.8.0 | *target*, B-10 |

Auth tier `password` means: answered only after `AUTH` when `requirepass` is set, otherwise to anyone.

## Handlers (code anchors)

| Group | Handler |
|---|---|
| Hash commands | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/HashCommands.kt` |
| The value | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/hashes/HashValue.kt` |

## Errors

| Condition | Reply | Where Redis says it |
|---|---|---|
| wrong type | `-WRONGTYPE Operation against a key holding the wrong kind of value` | `redis/redis@7.2!/src/server.c` |
| odd number of arguments to `HSET` | `-ERR wrong number of arguments for 'hset' command` | `hsetCommand` → `addReplyErrorArity` |
| `HINCRBY` increment not an integer | `-ERR value is not an integer or out of range` | `getLongLongFromObjectOrReply` |
| `HINCRBY` on a non-integer field | `-ERR hash value is not an integer` | `hincrbyCommand` |
| overflow | `-ERR increment or decrement would overflow` | `hincrbyCommand` |
| `HINCRBYFLOAT` increment not a float | `-ERR value is not a valid float` | `getLongDoubleFromObjectOrReply` |
| `HINCRBYFLOAT` increment infinite | `-ERR value is NaN or Infinity` | `hincrbyfloatCommand` |
| `HINCRBYFLOAT` on a non-float field | `-ERR hash value is not a float` | `hincrbyfloatCommand` |
| the result infinite | `-ERR increment would produce NaN or Infinity` | `hincrbyfloatCommand` |
| over `maxmemory` | `-OOM …` | *target*, B-11 |
