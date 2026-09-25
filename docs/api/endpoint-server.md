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
> `SAVE`/`BGSAVE`/`LASTSAVE` (B-14, B-25). The rest of Redis's server commands is not in kesh: the owner
ruled `SHUTDOWN`, `TIME`, `MEMORY USAGE` and `DEBUG SLEEP` out on 2026-09-25 — kore's ordered stop on
`SIGTERM` is the only way down. The authority on each command's syntax, reply and
> error is Redis's documentation, held by the differential harness against Redis 7.2
> ([conformance](../services/conformance.md)); error strings are read from Redis's source at `7.2`.

## Commands — all of them

| Command | Auth tier | In Redis since | Notes |
|---|---|---|---|
| `INFO [section …]` | password | 1.0.0 | built: `server`, `clients`, `memory`, `persistence`, `stats`, `keyspace`, in Redis's order; `default`, `all`, `everything` or none for all of them; a name kesh does not have adds nothing. Only fields whose meaning is Redis's — the harness holds each to Redis's report (`[fields]`) |
| `CONFIG GET / SET` | password | 2.0.0 | built: `maxmemory`, `maxmemory-policy`, `maxmemory-samples` ([feature-memory-limit](../features/feature-memory-limit.md)); `requirepass` and saving on stop are set from the environment only (`KESH_PASSWORD`, `KESH_SAVE_ON_SHUTDOWN`) |
| `SAVE`, `LASTSAVE` | password | 1.0.0 | built: a blocking save ([feature-snapshots](../features/feature-snapshots.md)) |
| `BGSAVE [SCHEDULE]` | password | 1.0.0 | built (B-25): a forked child writes the snapshot ([feature-snapshots](../features/feature-snapshots.md)) |

Auth tier `password` means: answered only after `AUTH` when `requirepass` is set, otherwise to anyone.

## Handlers (code anchors)

| Group | Handler |
|---|---|
| `INFO` | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/info/Info.kt` |
| `CONFIG` | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/config/MemoryConfig.kt` |
| `SAVE`, `BGSAVE`, `LASTSAVE` | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/persistence/Persistence.kt` |

## Errors

| Condition | Reply | Where Redis says it |
|---|---|---|
| `CONFIG SET maxmemory` above the container's budget | `-ERR CONFIG SET failed (possibly related to argument 'maxmemory') - maxmemory <n> (<human>) needs up to <ratio> x as much resident memory, more than the container's limit of <n> (<human>, <file>); at most <n> (<human>) fits` — kesh's own reason in Redis's frame (B-26, research §1.2, consequence 4) | the frame: `redis/redis@7.2!/src/config.c` `configSetCommand` |
| `SAVE` or `BGSAVE` while a child saves | `-ERR Background save already in progress` | `redis/redis@7.2!/src/rdb.c` `saveCommand`, `bgsaveCommand` |
| `BGSAVE` with an argument but `SCHEDULE` | `-ERR syntax error` | `redis/redis@7.2!/src/rdb.c` `bgsaveCommand` |
| command during snapshot load | none: the listener binds after the load, so no command can arrive (research D-24); Redis answers `-LOADING Redis is loading the dataset in memory` | `redis/redis@7.2!/src/server.c` `createSharedObjects` |
