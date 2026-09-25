---
id: feature-resp-connection
title: The protocol and the connection
type: feature
status: active
owner: unassigned
involved_services: [resp, server, conformance]
client_entries: []
api: [endpoint-connection]
tags: [protocol]
---

# The protocol and the connection

## 1. Overview

A client opens a TCP connection, optionally authenticates, and sends commands as RESP2 arrays of
bulk strings — one at a time or pipelined — or as inline commands typed into a telnet session. The
server answers in order. Any Redis client or tool connects without changes; the one Redis client the
portfolio uses today (Lettuce 7.6.0) opens with `HELLO 3`, receives `-NOPROTO`, and falls back to
RESP2 on its own (research §1.1).

## 2. Business rules

All built (B-01, B-02), each against Redis 7.2's source; the addresses are in research §1.5.

* Replies on one connection are written in the order its commands were received, pipelined or not.
* A request that does not start with `*` is an inline command, split as Redis's `sdssplitargs`
  splits it (quotes, escapes, `\xHH`); unbalanced quotes answer
  `-ERR Protocol error: unbalanced quotes in request`.
* Checks run in Redis's order: the command exists, then its arity, then authentication. So before
  `AUTH`, a command kesh does not have is "unknown command", not `NOAUTH` — as from Redis.
* With `requirepass` set, every command except `AUTH`, `HELLO` and `QUIT` answers
  `-NOAUTH Authentication required.` until `AUTH` succeeds. A wrong password answers
  `-WRONGPASS invalid username-password pair or user is disabled.` (not the brief's
  `-ERR invalid password`). The only user is `default`.
* **Before `AUTH`, requests are limited** as Redis 7.2 limits them: more than 10 arguments
  (`unauthenticated multibulk length`) or a bulk over 16 384 bytes (`unauthenticated bulk length`) is
  a protocol error. Until the connection is authenticated, each command is parsed only after the
  previous one ran, so an `AUTH` lifts the limits for the very next command of the same pipeline.
* A protocol error answers `-ERR Protocol error: <condition>` — Redis's string for that condition —
  after the replies to the commands before it, and closes the connection.
* A bulk over `proto-max-bulk-len` (512 MB) is `invalid bulk length`; a line running 64 KB without its
  ending is `too big inline request` / `too big mbulk count string` / `too big bulk count string`.
  A connection holding more than `client-query-buffer-limit` (1 GB) unparsed is closed without a
  reply, as Redis closes it.
* `HELLO 3` answers `-NOPROTO unsupported protocol version`; the connection stays in RESP2.
* `SELECT 0` succeeds; any other index answers `-ERR DB index is out of range`.
* `QUIT` answers `+OK` and closes; what was pipelined after it is dropped.
* `POST` or `Host:` as a command — an HTTP request aimed at the port — closes the connection without
  a reply, as Redis does against cross-protocol scripting.
* **At most `maxclients` connections**, by default what keeps every descriptor under the transport's
  `FD_SETSIZE` (research D-13; 986 on the build machine). The connection over it gets
  `-ERR max number of clients reached` and is closed, and the others keep being served.

## 3. Flow

1. `server` accepts, and registers the client on the store thread — where the `maxclients` count
   lives — or refuses it.
2. Bytes go to `resp`'s incremental parser. Authenticated, every command completed by a read goes to
   the store thread in one hand-off (research D-14); before `AUTH`, one at a time.
3. Replies are written back in the same order, one write per batch.

## 4. Code anchors

| Service | Code |
|---|---|
| resp | `resp/src/commonMain/kotlin/io/github/youndie/kesh/resp/CommandReader.kt` — the parser and its limits |
| resp | `resp/src/commonMain/kotlin/io/github/youndie/kesh/resp/InlineArguments.kt` — `sdssplitargs` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/connection/Connection.kt` — the per-connection loop |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/command/CommandDispatcher.kt` — the connection commands |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/client/Clients.kt` — the registry and the ceiling |
| conformance | `conformance/scripts/connection/` — every command here against Redis 7.2, byte for byte (B-04) |

## 5. Scenarios

All automated since B-05, which brought the data commands three of them needed. Beside the scenarios, every command and refusal of this feature is compared with Redis 7.2 byte for
byte by `conformance/run.sh` — 78 comparisons, all agreeing, at B-04.

### Scenario: Ping
* **Given:** a new connection
* **When:** the client sends `PING`
* **Then:** the reply is `+PONG`
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/KeshServerTest.kt::PING over TCP answers PONG`

### Scenario: Unknown command
* **Given:** a new connection
* **When:** the client sends `FOO a b`
* **Then:** the reply is `-ERR unknown command 'FOO', with args beginning with: 'a' 'b' ` — trailing space included, as Redis 7.2 sends it
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/command/CommandDispatcherTest.kt::an unknown command quotes each argument and ends with a space as Redis does`

### Scenario: Protocol error closes the connection
* **Given:** a new connection
* **When:** the client sends `*1\r\n$x\r\n`
* **Then:** the reply is `-ERR Protocol error: invalid bulk length` and the server closes the connection
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/KeshServerTest.kt::a protocol error is answered and the connection closed`

### Scenario: Pipelined order
* **Given:** a connection and `k` absent
* **When:** the client sends 1 000 `INCR k` without reading
* **Then:** it reads 1 000 integer replies, `:1` to `:1000`, in order
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/ConnectionScenariosTest.kt::a thousand pipelined INCR are answered 1 to 1000 in order`

### Scenario: Inline
* **Given:** a telnet session
* **When:** the user types `EXISTS somekey`
* **Then:** the reply is `:0`
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/ConnectionScenariosTest.kt::EXISTS typed into telnet answers 0 for a missing key`

### Scenario: Auth required
* **Given:** `requirepass` is set
* **When:** an unauthenticated client sends `GET k`
* **Then:** the reply is `-NOAUTH Authentication required.`
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/ConnectionScenariosTest.kt::GET before AUTH is refused with NOAUTH`

### Scenario: AUTH opens the connection
* **Given:** `requirepass` is set
* **When:** an unauthenticated client sends `PING`, then `AUTH <password>` and `PING`
* **Then:** the replies are `-NOAUTH Authentication required.`, `+OK`, `+PONG`
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/ConnectionScenariosTest.kt::with a password an unauthenticated command gets NOAUTH and AUTH opens the connection`

### Scenario: Oversized request before AUTH
* **Given:** `requirepass` is set and the client has not authenticated
* **When:** it sends `SET k <16 385 bytes>`
* **Then:** the reply is `-ERR Protocol error: unauthenticated bulk length` and the connection is closed
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/ConnectionScenariosTest.kt::an oversized request before AUTH is refused and the connection closed`

### Scenario: AUTH lifts the limits for the next pipelined command
* **Given:** `requirepass` is set
* **When:** one write carries `AUTH <password>` and an `ECHO` of 20 000 bytes
* **Then:** both are answered: `+OK`, then the 20 000 bytes
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/ConnectionScenariosTest.kt::AUTH lifts the unauthenticated limits for the very next command of the same pipeline`

### Scenario: RESP3 asked
* **When:** a client sends `HELLO 3`
* **Then:** the reply is `-NOPROTO unsupported protocol version`
* **And:** the next `PING` on the same connection answers `+PONG` in RESP2
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/ConnectionScenariosTest.kt::HELLO 3 is refused and the connection stays in RESP2`

### Scenario: Lettuce connects unchanged
* **Given:** Lettuce 7.6.0 with default options
* **When:** it connects and runs `SET` then `GET`
* **Then:** both succeed; the handshake fell back from `HELLO 3` to RESP2 without an error surfacing
* **Automated:** `conformance/src/jvmMain/kotlin/io/github/youndie/kesh/conformance/Main.kt::lettuceSmoke` — on every `conformance/run.sh`

### Scenario: Connection ceiling
* **Given:** `maxclients` N and N open connections
* **When:** connection N+1 opens
* **Then:** it receives `-ERR max number of clients reached` and is closed
* **And:** the first N connections still answer `PING`
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/ConnectionScenariosTest.kt::the connection over maxclients is refused and the others keep being served`

### Scenario: QUIT
* **When:** a client sends `QUIT` and, in the same write, `PING`
* **Then:** it reads `+OK` and the connection is closed; the `PING` is not answered
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/ConnectionScenariosTest.kt::QUIT answers and closes and drops what was pipelined after it`

### Scenario: Cross-protocol request
* **When:** an HTTP `POST` arrives on the port
* **Then:** the connection is closed without a reply
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/ConnectionScenariosTest.kt::an HTTP request aimed at the port is dropped without a reply`

### Scenario: Garbage does not take the server down
* **Given:** 200 connections that send random bytes
* **When:** a new client sends `PING`
* **Then:** it gets `+PONG`
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/ConnectionScenariosTest.kt::garbage on hundreds of connections leaves the server answering`

## 6. Out of scope

* RESP3, `CLIENT TRACKING`, ACL users beyond one password (brief §2).
* Pub/Sub — a feature of its own (`feature-pubsub`, B-27), added to v1 by the owner (research Q-1,
  D-26). Until B-27, a subscribe command is an unknown command.

## 7. Quirks

* **Commands pipelined behind a protocol error get no reply**: the connection is closed after the
  error, as in Redis.
* **An unauthenticated client asking for a command kesh lacks gets "unknown command"**, not
  `NOAUTH`: the existence check comes first, in Redis too.
* **Before `AUTH` a pipeline is parsed one command at a time**, and so is slower — deliberately, so
  that `AUTH` takes effect for the command right after it.
* **Any signal can kill the process through the selector's `pselect`** (research R-3) — relevant to
  anyone who reaches for an in-process profiler.

---

Why it is built this way, and what is still a hypothesis: [research-architecture](../research/research-architecture.md).
