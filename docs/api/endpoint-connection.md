---
id: endpoint-connection
title: Connection commands
type: api_endpoints
status: active
services:
  - server
  - resp
contract_source:
  - "kesh:server CommandDispatcher"
parent_feature: feature-resp-connection
---

# API: connection commands

Connection state: authentication, protocol version, the (single) database, client identity. Part of [feature-resp-connection](../features/feature-resp-connection.md).

> **Built in B-01:** `PING`, the unknown-command error, the arity error, and three protocol errors
> (invalid multibulk length, invalid bulk length, expected `$`). **Everything else is *target*,
> B-02.** The authority on each command's syntax, reply and error is Redis's documentation of that
> command, held by the differential harness against Redis 7.2 once B-04 builds it. Error strings
> below were read from Redis's source at the `7.2` branch unless the row says otherwise.

## Commands — all of them

| Command | Auth tier | In Redis since | Notes |
|---|---|---|---|
| `PING [message]` | password | 1.0.0 | liveness of the connection — **built** |
| `ECHO message` | password | 1.0.0 | echo |
| `QUIT` | none | 1.0.0 | close after `+OK` |
| `AUTH [username] password` | none | 1.0.0 | authenticate against `requirepass`; the only accepted username is `default` |
| `HELLO [protover [AUTH username password] [SETNAME name]]` | none | 6.0.0 | RESP2 only; `HELLO 3` → `-NOPROTO` |
| `SELECT index` | password | 1.0.0 | `0` only |
| `CLIENT SETNAME / GETNAME / ID / LIST / KILL` | password | 2.4.0–5.0.0 | connection identity |
| `CLIENT SETINFO` | password | 7.2.0 | accepted with `+OK` — Lettuce sends it and ignores failure either way (research §1.1) |
| `COMMAND [COUNT / INFO / DOCS]` | password | 2.8.13 | enough for client handshakes that call it |

Auth tier `password` means: answered only after `AUTH` when `requirepass` is set, otherwise to anyone.

## Handlers (code anchors)

| Group | Handler |
|---|---|
| Connection commands | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/connection/` |
| Connection commands | `resp/src/commonMain/kotlin/io/github/youndie/kesh/resp/CommandReader.kt` — protocol errors |
| Connection commands | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/command/CommandDispatcher.kt` — `PING`, unknown command, arity |

## Errors

| Condition | Reply | Where Redis says it |
|---|---|---|
| unauthenticated command | `-NOAUTH Authentication required.` | `redis/redis@7.2!/src/server.c` `createSharedObjects` |
| wrong password | `-WRONGPASS invalid username-password pair or user is disabled.` — not the brief's `-ERR invalid password` | `redis/redis@7.2!/src/acl.c` |
| `HELLO 3` | `-NOPROTO unsupported protocol version` | `redis/redis@7.2!/src/networking.c` `helloCommand` |
| `SELECT 1` | `-ERR DB index is out of range` | `redis/redis@7.2!/src/db.c` |
| too many clients | `-ERR max number of clients reached`, then close | `redis/redis@7.2!/src/networking.c` |
| malformed frame | `-ERR Protocol error: <condition>`, then close — one string per condition | `redis/redis@7.2!/src/networking.c` `processMultibulkBuffer`, `processInlineBuffer` |
| before `AUTH`: > 10 arguments / bulk > 16 384 B | `-ERR Protocol error: unauthenticated multibulk length` / `… unauthenticated bulk length`, then close | `redis/redis@7.2!/src/networking.c` |
| unknown command | `-ERR unknown command '<name>', with args beginning with: <args>` | `redis/redis@7.2!/src/server.c` `processCommand` |
