---
id: endpoint-server
title: Server commands
type: api_endpoints
status: active
services:
  - server
  - snapshot
  - store
contract_source:
  - "kesh:server CommandDispatcher"
parent_feature: feature-operations
---

# API: server commands

Introspection, configuration, persistence, shutdown. Part of [feature-operations](../features/feature-operations.md).

> **Partly built.** `INFO` (B-11, B-13, B-15), `CONFIG GET`/`SET` for memory (B-11, B-12),
> `SAVE`/`LASTSAVE` (B-14). The rest is *target*. The authority on each command's syntax, reply and
> error is Redis's documentation, held by the differential harness against Redis 7.2
> ([conformance](../services/conformance.md)); error strings are read from Redis's source at `7.2`.

## Commands — all of them

| Command | Auth tier | In Redis since | Notes |
|---|---|---|---|
| `INFO [section …]` | password | 1.0.0 | built: `server`, `clients`, `memory`, `persistence`, `stats`, `keyspace`, in Redis's order; `default`, `all`, `everything` or none for all of them; a name kesh does not have adds nothing. Only fields whose meaning is Redis's — the harness holds each to Redis's report (`[fields]`) |
| `CONFIG GET / SET` | password | 2.0.0 | built: `maxmemory`, `maxmemory-policy`, `maxmemory-samples` ([feature-memory-limit](../features/feature-memory-limit.md)); *target*: `requirepass`, `save-on-shutdown` |
| `SAVE`, `LASTSAVE` | password | 1.0.0 | built: a blocking save ([feature-snapshots](../features/feature-snapshots.md)) |
| `SHUTDOWN [SAVE\|NOSAVE]` | password | 1.0.0 | *target*: through kore's ordered stop (B-16) |
| `TIME` | password | 2.6.0 | *target* |
| `MEMORY USAGE key` | password | 4.0.0 | *target*: kesh's own estimate (research D-10) |
| `DEBUG SLEEP` | password | — | *target*: tests only; off unless a flag enables it |

Auth tier `password` means: answered only after `AUTH` when `requirepass` is set, otherwise to anyone.

## Handlers (code anchors)

| Group | Handler |
|---|---|
| `INFO` | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/info/Info.kt` |
| `CONFIG` | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/config/MemoryConfig.kt` |
| `SAVE`, `LASTSAVE` | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/persistence/Persistence.kt` |

## Errors

| Condition | Reply | Where Redis says it |
|---|---|---|
| `CONFIG SET maxmemory` above the container's budget | *target*, B-26: a kesh-specific refusal naming both numbers (research §1.2, consequence 4) | — |
| command during snapshot load | none: the listener binds after the load, so no command can arrive (research D-24); Redis answers `-LOADING Redis is loading the dataset in memory` | `redis/redis@7.2!/src/server.c` `createSharedObjects` |
