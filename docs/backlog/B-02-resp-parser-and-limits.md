---
id: B-02
title: "resp: parser and writer, pipelining, inline commands, limits and the connection ceiling"
status: done
priority: P0
size: M
stage: stage-1-protocol
epic: feature-resp-connection
blocked_by: [B-01]
---

# B-02 — resp: parser and writer, pipelining, inline commands, limits and the connection ceiling

**Feature:** [feature-resp-connection](../features/feature-resp-connection.md).

The protocol is the product's surface: every client and tool talks to kesh only through it. It has
to accept exactly what Redis accepts and refuse exactly what Redis refuses, with Redis's words,
because clients parse the error prefix.

- **Error strings come from Redis's source, not from the brief** (research §1.5). The brief lists
  `-ERR Protocol error: …`; Redis has one string per condition (`invalid bulk length`,
  `invalid multibulk length`, `too big inline request`, `unbalanced quotes in request`,
  `expected '$', got '<c>'`, …) and closes the connection after replying.
- **Before `AUTH`, requests are limited** as in Redis 7.2: more than 10 arguments →
  `Protocol error: unauthenticated multibulk length`; a bulk over 16 384 bytes →
  `Protocol error: unauthenticated bulk length`. The brief does not list these; a conformance
  script hits them on the first run.
- **A connection ceiling below `FD_SETSIZE`** (research §1.4, D-13): `ktor-network` (3.6.0, as 3.5.2) throws
  inside its selector for a descriptor ≥ 1024. `maxclients` defaults below that with headroom for
  the listener, HTTP, snapshot and stdio descriptors; the connection over the ceiling gets
  `-ERR max number of clients reached` and is closed. The chosen default and its arithmetic are
  written into research D-13 in the same pull request.
- Rejected: a parser that is lenient about framing "to be friendly" — a lenient parser is a parser
  that disagrees with the oracle.
- Not covered: command semantics beyond `PING`, `ECHO`, `QUIT`, `AUTH`, `HELLO`, `SELECT`,
  `CLIENT`, `COMMAND`.

- AC: The connection scenarios of `feature-resp-connection` pass against the binary.
- AC: A fuzz test of malformed input (truncated frames, negative and oversized lengths, missing CRLF, binary garbage) never crashes the server and never leaves a connection half-parsed.
- AC: With `maxclients` N, connection N+1 receives `-ERR max number of clients reached`, and the first N keep being served.
- AC: Before `AUTH`, an 11-argument request and a 16 385-byte bulk are refused with Redis's two strings.

## Findings

### Iteration 1 — 2026-09-24, done

Acceptance, on the Linux build machine with the release binary:

- The feature's scenarios: 11 of 14 automated (`ConnectionScenariosTest`, `CommandDispatcherTest`,
  `KeshServerTest`); the three left are *target* for reasons outside this item — `INCR` and `EXISTS`
  (B-05), a Lettuce client (B-04) — and each names what covers its protocol half today.
- `redis-cli` 7.2.16 and `nc`: `NOAUTH`, `WRONGPASS` via `-a`, `PONG` after it; `AUTH` + `HELLO 3` +
  `PING` on one connection → `+OK`, `-NOPROTO …`, `+PONG`; an inline quoted `ECHO`, `SELECT 1`,
  `CLIENT SETNAME`/`GETNAME`, `QUIT` dropping the `PING` after it; `CLIENT LIST` with Redis's keys.
- Fuzz: 20 000 seeded random inputs in random pieces through `CommandReader` (JVM and linuxX64) —
  only commands, `null` or `ProtocolException`; its positive control (a bulk read past the buffer)
  fails it. Through sockets: 200 connections of garbage, then `PING` → `PONG`.
- `maxclients`: the derived default is 986 on this machine (`FD_SETSIZE` 1024 − 6 open − 32
  reserved, research D-13). 1 100 connections held open from Python: 986 served, 114 refused with
  `-ERR max number of clients reached`, all 986 still answering, recovery after release, exit 0.
- Before `AUTH`: 11 arguments → `unauthenticated multibulk length`, a 16 385-byte bulk →
  `unauthenticated bulk length`, each followed by a closed connection.
- 103 tests (resp 32 × 2 targets, server 39), `ktlintCheck` clean, five repeated server runs clean.
  Mutations caught: the unauthenticated bulk limit ignored; the ceiling off by one; the auth gate
  removed; batching before `AUTH`; `POST` answered; no query-buffer limit; a glued quote accepted;
  a bulk read past the buffer.

Found on the way, decided here:

- **Redis checks existence and arity before authentication**, so an unauthenticated unknown command
  is "unknown command". The brief's *Auth required* scenario used `GET k`, which would read so until
  B-05; the scenario now uses `PING`.
- **`POST` and `Host:` close the connection without a reply** (Redis's cross-protocol-scripting
  guard) — not in the brief, necessary once inline commands exist.
- **`AUTH` mid-pipeline**: before `AUTH` each command is parsed only after the previous one ran, so
  the unauthenticated limits stop at the right command.
- **A peer reset is not a failure**: the flood logged 318 "connection failed" lines until it was
  treated as a close.
- **An `accept` that fails is retried**, so running out of descriptors no longer ends the accept loop.
- **`HELLO` reports `server: kesh`, `version: 7.2.0`** — research D-18.

Not done here, on purpose: `COMMAND` entries without key specs and `COMMAND DOCS` empty (the data
commands fill them, B-05 on); `CLIENT LIST` values kesh does not track written as zero, `fd=-1`.

## Code anchors

| Module | Path |
|---|---|
| resp | `resp/src/commonMain/kotlin/io/github/youndie/kesh/resp/CommandReader.kt` |
| resp | `resp/src/commonMain/kotlin/io/github/youndie/kesh/resp/InlineArguments.kt` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/command/CommandDispatcher.kt` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/client/DescriptorCeiling.kt` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/connection/Connection.kt` |

Research: [research-architecture](../research/research-architecture.md).
