---
id: endpoint-strings
title: String commands
type: api_endpoints
status: active
services:
  - store
  - server
contract_source:
  - "kesh:store StoreCommand"
parent_feature: feature-strings
---

# API: string commands

Strings and integer counters. Part of [feature-strings](../features/feature-strings.md).

> **Built (B-05).** The authority on each command's syntax, reply and error is Redis's documentation
> of that command, held by the differential harness against Redis 7.2
> ([conformance](../services/conformance.md)) — every command here is in its scripts. Error strings
> below were read from Redis's source at the `7.2` branch unless the row says otherwise.

## Commands — all of them

| Command | Auth tier | In Redis since | Notes |
|---|---|---|---|
| `GET, SET [NX\|XX] [GET] [EX\|PX\|EXAT\|PXAT\|KEEPTTL]` | password | 1.0.0; options to 7.0.0 | value; `SET` without `KEEPTTL` clears an expiry |
| `SETNX, SETEX, PSETEX, GETSET` | password | 1.0.0–2.6.0 | legacy forms |
| `GETDEL, GETEX` | password | 6.2.0 |  |
| `MGET, MSET, MSETNX` | password | 1.0.1 |  |
| `APPEND, STRLEN, GETRANGE, SETRANGE` | password | 2.0.0–2.2.0 |  |
| `INCR, INCRBY, DECR, DECRBY, INCRBYFLOAT` | password | 1.0.0–2.6.0 | signed 64-bit counters |

Auth tier `password` means: answered only after `AUTH` when `requirepass` is set, otherwise to anyone.

## Handlers (code anchors)

| Group | Handler |
|---|---|
| String commands | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/StringCommands.kt` |
| String commands | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/RedisFloat.kt` — `INCRBYFLOAT` |

## Errors

| Condition | Reply | Where Redis says it |
|---|---|---|
| wrong type | `-WRONGTYPE Operation against a key holding the wrong kind of value` | `redis/redis@7.2!/src/server.c` |
| not an integer | `-ERR value is not an integer or out of range` | `redis/redis@7.2!/src/object.c` |
| overflow | `-ERR increment or decrement would overflow` | `redis/redis@7.2!/src/t_string.c` `incrDecrCommand` |
| `DECRBY k -9223372036854775808` | `-ERR decrement would overflow` | `redis/redis@7.2!/src/t_string.c` `decrbyCommand` |
| `INCRBYFLOAT` to NaN/Inf | `-ERR increment would produce NaN or Infinity` | `redis/redis@7.2!/src/t_string.c` |
| not a float | `-ERR value is not a valid float` | `redis/redis@7.2!/src/object.c` |
| bad expire | `-ERR invalid expire time in '<command>' command` | `redis/redis@7.2!/src/networking.c` `addReplyErrorExpireTime` |
| `SETRANGE` offset | `-ERR offset is out of range` | `redis/redis@7.2!/src/t_string.c` |
| value over the bulk limit | `-ERR string exceeds maximum allowed size (proto-max-bulk-len)` | `redis/redis@7.2!/src/t_string.c` |
| bad option | `-ERR syntax error` | `redis/redis@7.2!/src/server.c` |
| over `maxmemory`, `noeviction` | `-OOM command not allowed when used memory > 'maxmemory'.` | `redis/redis@7.2!/src/server.c` |

## Quirks

* **`INCRBYFLOAT` computes in `Double`**, not x86's `long double`: `0.1 + 0.2` answers
  `0.30000000000000004` where Redis on x86-64 answers `0.3` ([feature-strings](../features/feature-strings.md)).
