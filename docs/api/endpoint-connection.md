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

> **Built (B-01, B-02).** The authority on each command's syntax, reply and error is Redis's
> documentation of that command, held by the differential harness against Redis 7.2 once B-04 builds
> it. Until then every string below was read from Redis's source at the `7.2` branch, and the tests
> in `CommandDispatcherTest.kt` assert them byte for byte.

## Commands — all of them

| Command | Auth tier | In Redis since | Notes |
|---|---|---|---|
| `PING [message]` | password | 1.0.0 | `+PONG`, or the message as a bulk string |
| `ECHO message` | password | 1.0.0 | the message as a bulk string |
| `QUIT` | none | 1.0.0 | `+OK`, then close; what was pipelined after it is dropped |
| `AUTH [username] password` | none | 1.0.0 | against `requirepass`; the only user is `default`; compared in constant time |
| `HELLO [protover [AUTH username password] [SETNAME name]]` | none | 6.0.0 | RESP2 only: `HELLO 3` → `-NOPROTO`; the reply names `server` `kesh`, `version` `7.2.0` (research D-18) |
| `SELECT index` | password | 1.0.0 | `0` only |
| `CLIENT SETNAME / GETNAME / ID / LIST / KILL / SETINFO / HELP` | password | 2.4.0–7.2.0 | `LIST` takes `TYPE` and `ID`; `KILL` the old `ip:port` form and `ID`, `TYPE`, `ADDR`, `LADDR`, `USER`, `SKIPME` (Redis 7.2 has no `MAXAGE`) |
| `COMMAND [COUNT / INFO / DOCS / HELP]` | password | 2.8.13 | entries carry name, arity, `no_auth` and subcommands; `DOCS` answers an empty list |

Auth tier `password` means: answered only after `AUTH` when `requirepass` is set, otherwise to anyone.

## Handlers (code anchors)

| Group | Handler |
|---|---|
| Connection commands | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/command/CommandDispatcher.kt` — the command table, the order of checks, every command above |
| Connection commands | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/connection/Connection.kt` — `QUIT`, closing, the query-buffer limit |
| Connection commands | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/KeshServer.kt` — `maxclients` refusal |
| Connection commands | `resp/src/commonMain/kotlin/io/github/youndie/kesh/resp/CommandReader.kt` — protocol errors and request limits |

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
| unknown subcommand | `-ERR unknown subcommand '<sub>'. Try CLIENT HELP.` | `redis/redis@7.2!/src/server.c` `commandCheckExistence` |
| wrong arity | `-ERR wrong number of arguments for '<command>' command`, subcommands as `client\|setname` | `redis/redis@7.2!/src/server.c` `commandCheckArity` |
| `AUTH password` with no `requirepass` | `-ERR AUTH <password> called without any password configured for the default user. Are you sure your configuration is correct?` | `redis/redis@7.2!/src/acl.c` `authCommand` |
| `HELLO` before `AUTH` without its `AUTH` option | `-NOAUTH HELLO must be called with the client already authenticated, otherwise the HELLO <proto> AUTH <user> <pass> option can be used …` | `redis/redis@7.2!/src/networking.c` `helloCommand` |
| `HELLO x` / bad option | `-ERR Protocol version is not an integer or out of range` / `-ERR Syntax error in HELLO option '<opt>'` | `redis/redis@7.2!/src/networking.c` `helloCommand` |
| a name with a space or control byte | `-ERR Client names cannot contain spaces, newlines or special characters.` | `redis/redis@7.2!/src/networking.c` `validateClientName` |
| `CLIENT SETINFO` | `-ERR Unrecognized option '<attr>'` / `-ERR <attr> cannot contain spaces, newlines or special characters.` | `redis/redis@7.2!/src/networking.c` `clientSetinfoCommand` |
| `CLIENT KILL` | `-ERR No such client` (old form), `-ERR client-id should be greater than 0`, `-ERR Unknown client type '<t>'`, `-ERR No such user '<u>'` | `redis/redis@7.2!/src/networking.c` `clientCommand` |
| `CLIENT LIST ID x` | `-ERR Invalid client ID` | `redis/redis@7.2!/src/networking.c` `clientCommand` |
| inline: unbalanced quotes / 64 KB without a line ending | `-ERR Protocol error: unbalanced quotes in request` / `… too big inline request`, then close | `redis/redis@7.2!/src/networking.c` `processInlineBuffer` |
| `POST` or `Host:` as a command | no reply; the connection is closed | `redis/redis@7.2!/src/networking.c` `securityWarningCommand` |
| more than `client-query-buffer-limit` unparsed | no reply; the connection is closed | `redis/redis@7.2!/src/networking.c` |

## Quirks

* **`CLIENT LIST` writes `fd=-1`** and zero for the memory and buffer fields: the transport does not
  expose the descriptor, and kesh does not track those figures. The keys and their order are Redis's,
  so tools that split the line find what they look for.
* **`COMMAND` entries carry no key positions, ACL categories, tips or key specs yet**: no command here
  takes a key. The data commands (B-05 onwards) fill them in.
