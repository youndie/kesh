---
id: endpoint-keyspace
title: Keyspace commands
type: api_endpoints
status: active
services:
  - store
  - server
contract_source:
  - "kesh:store StoreCommand"
parent_feature: feature-keyspace
---

# API: keyspace commands

Commands on keys of any type: delete, test, expire, rename, iterate. Part of [feature-keyspace](../features/feature-keyspace.md).

> **Built (B-05).** The authority on each command's syntax, reply and error is Redis's documentation
> of that command, held by the differential harness against Redis 7.2
> ([conformance](../services/conformance.md)) — every command here is in its scripts. Error strings
> below were read from Redis's source at the `7.2` branch unless the row says otherwise.

## Commands — all of them

| Command | Auth tier | In Redis since | Notes |
|---|---|---|---|
| `DEL, UNLINK` | password | 1.0.0 / 4.0.0 | identical in kesh (research D-15) |
| `EXISTS, TYPE` | password | 1.0.0 |  |
| `EXPIRE, PEXPIRE, EXPIREAT, PEXPIREAT [NX\|XX\|GT\|LT]` | password | 1.0.0–2.6.0; options 7.0.0 |  |
| `TTL, PTTL, EXPIRETIME, PEXPIRETIME, PERSIST` | password | 1.0.0–7.0.0 | `-1` no expiry, `-2` no key |
| `RENAME, RENAMENX` | password | 1.0.0 |  |
| `SCAN cursor [MATCH] [COUNT] [TYPE]` | password | 2.8.0; `TYPE` 6.0.0 | the completeness guarantee |
| `KEYS pattern` | password | 1.0.0 | documented as unsuitable for production, as in Redis |
| `RANDOMKEY, DBSIZE` | password | 1.0.0 |  |
| `FLUSHALL, FLUSHDB [ASYNC\|SYNC]` | password | 1.0.0 | `ASYNC` accepted, same as sync (research D-15) |

Auth tier `password` means: answered only after `AUTH` when `requirepass` is set, otherwise to anyone.

## Handlers (code anchors)

| Group | Handler |
|---|---|
| Keyspace commands | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/KeyCommands.kt` |
| Keyspace commands | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/Db.kt` — lazy expiry |

## Errors

| Condition | Reply | Where Redis says it |
|---|---|---|
| `RENAME` of a missing key | `-ERR no such key` | `redis/redis@7.2!/src/server.c` |
| bad expire | `-ERR invalid expire time in '<command>' command` | `redis/redis@7.2!/src/networking.c` |
| `NX` with `XX`/`GT`/`LT` | `-ERR NX and XX, GT or LT options at the same time are not compatible` | `redis/redis@7.2!/src/expire.c` |
| `GT` with `LT` | `-ERR GT and LT options at the same time are not compatible` | `redis/redis@7.2!/src/expire.c` |
| bad cursor | `-ERR invalid cursor` | `redis/redis@7.2!/src/db.c` |
| `SCAN … TYPE` unknown | `-ERR unknown type name '<t>'` | `redis/redis@7.2!/src/db.c` |

## Quirks

* **`SCAN` is *target* (B-10).** Every other command here is built (B-05).
* **`KEYS` answers in table order**, which is neither insertion order nor Redis's order; conformance
  compares it as a multiset, as Redis promises no order either.
* **`DBSIZE` counts expired keys nothing has looked up yet**, as Redis's does; active expiry (B-13)
  makes that window short.
