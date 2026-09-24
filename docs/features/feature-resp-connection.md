---
id: feature-resp-connection
title: The protocol and the connection
type: feature
status: active
owner: unassigned
involved_services: [resp, server]
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

Built in B-01: reply order (pipelined or not), and the protocol-error rule for a malformed multibulk
length, a malformed bulk length and a non-bulk argument. The rest are *target*, B-02.

* Replies on one connection are written in the order its commands were received, pipelined or not.
* A request that is not a RESP array is parsed as an inline command.
* With `requirepass` set, every command except `AUTH`, `HELLO` and `QUIT` answers
  `-NOAUTH Authentication required.` until `AUTH` succeeds. A wrong password answers
  `-WRONGPASS invalid username-password pair or user is disabled.` (not the brief's
  `-ERR invalid password`, which is not what Redis 7.2 says — research §1.5).
* **Before `AUTH`, requests are limited** as Redis 7.2 limits them: more than 10 arguments, or a bulk
  over 16 384 bytes, is a protocol error and closes the connection (research §1.5).
* A protocol error answers `-ERR Protocol error: <condition>` — Redis's string for that condition —
  and closes the connection.
* A request larger than `proto-max-bulk-len` or `client-query-buffer-limit` is refused and the
  connection closed.
* `HELLO 3` answers `-NOPROTO unsupported protocol version`; the connection stays in RESP2.
* `SELECT 0` succeeds; any other index answers `-ERR DB index is out of range`.
* **At most `maxclients` connections**, set below the transport's `FD_SETSIZE` ceiling (research D-13);
  the connection over it gets `-ERR max number of clients reached` and is closed, and the others keep
  being served.

## 3. Flow

1. `server` accepts; the connection is counted against `maxclients` first.
2. Bytes go to `resp`'s incremental parser; each complete command is queued to the store thread in
   arrival order (research D-14).
3. Replies are written back in the same order, flushed per read batch, so a pipeline of 1 000
   commands becomes a handful of writes.

## 4. Code anchors

| Service | Code |
|---|---|
| resp | `resp/src/commonMain/kotlin/io/github/youndie/kesh/resp/CommandReader.kt` — the parser |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/connection/Connection.kt` — the per-connection loop |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/command/CommandDispatcher.kt` — `PING`, unknown commands |

The conformance scripts for this feature arrive with B-04, in the `conformance` module.

## 5. Scenarios

Built in B-01: *Ping*, *Unknown command*, *Protocol error closes the connection*. Every other
scenario is *target* — B-02 (connection state, inline, limits, the ceiling), B-05 (`INCR`), B-04
(the oracle).

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
*Target* until `INCR` exists (B-05); the ordering itself is covered today with 200 pipelined `PING <n>`
by `KeshServerTest.kt::pipelined commands are answered in the order they were sent`.
* **Given:** a connection and `k` absent
* **When:** the client sends 1 000 `INCR k` without reading
* **Then:** it reads 1 000 integer replies, `:1` to `:1000`, in order

### Scenario: Inline
* **Given:** a telnet session
* **When:** the user types `EXISTS somekey`
* **Then:** the reply is `:0`

### Scenario: Auth required
* **Given:** `requirepass` is set
* **When:** an unauthenticated client sends `GET k`
* **Then:** the reply is `-NOAUTH Authentication required.`

### Scenario: Oversized request before AUTH
* **Given:** `requirepass` is set and the client has not authenticated
* **When:** it sends `SET k <16 385 bytes>`
* **Then:** the reply is `-ERR Protocol error: unauthenticated bulk length` and the connection is closed

### Scenario: RESP3 asked
* **When:** a client sends `HELLO 3`
* **Then:** the reply is `-NOPROTO unsupported protocol version`
* **And:** the next `PING` on the same connection answers `+PONG` in RESP2

### Scenario: Lettuce connects unchanged
* **Given:** Lettuce 7.6.0 with default options
* **When:** it connects and runs `SET` then `GET`
* **Then:** both succeed; the handshake fell back from `HELLO 3` to RESP2 without an error surfacing

### Scenario: Connection ceiling
* **Given:** `maxclients` N and N open connections
* **When:** connection N+1 opens and sends `PING`
* **Then:** it receives `-ERR max number of clients reached` and is closed
* **And:** the first N connections still answer `PING`

## 6. Out of scope

* RESP3, `CLIENT TRACKING`, ACL users beyond one password (brief §2).
* Pub/Sub — open, research Q-1 / B-21.

## 7. Quirks

* **Commands pipelined behind a protocol error get no reply**: the connection is closed after the
  error, as in Redis.
* **Any signal can kill the process through the selector's `pselect`** (research R-3) — relevant to
  anyone who reaches for an in-process profiler.

---

Why it is built this way, and what is still a hypothesis: [research-architecture](../research/research-architecture.md).
