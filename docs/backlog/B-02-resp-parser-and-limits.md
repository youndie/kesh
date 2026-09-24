---
id: B-02
title: "resp: parser and writer, pipelining, inline commands, limits and the connection ceiling"
status: wip
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

## Code anchors

| Module | Path |
|---|---|
| resp | `resp/src/commonMain/kotlin/io/github/youndie/kesh/resp/` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/connection/` |

Research: [research-architecture](../research/research-architecture.md).
