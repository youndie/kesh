---
id: resp
title: resp — the RESP2 parser and writer
type: service
status: active
module: resp
tech_stack: [Kotlin, common source set, jvm and linuxX64 targets]
owner: unassigned
depends_on: []
publishes: []
---

# resp

## 1. Responsibility

Turns bytes from a connection into commands and replies into bytes, and — from B-02 — enforces the
protocol's limits while doing it. It is the only place that knows the wire format.

**Deliberately does not:** know any command's meaning, touch the keyspace, own a socket, or speak
RESP3 (research D-1; `HELLO 3` is refused one layer up, in `server`).

## 2. API contracts

* **The wire:** RESP2 as Redis's protocol specification describes it. Pipelining is a property of
  the parser: it yields every complete command in a buffer, in order.
* **Built (B-01, B-02):** multibulk requests and inline commands (split as Redis's
  `sdssplitargs`); integers parsed as Redis's `string2ll` (no sign on positives, no leading zeros);
  `proto-max-bulk-len`; the 64 KB line limit; and before `AUTH` the two limits Redis 7.2 enforces —
  more than 10 arguments, a bulk over 16 384 bytes (research §1.5). Every refusal carries Redis's
  own words. `client-query-buffer-limit` is enforced by `server`, from `CommandReader.buffered`.
* **Errors:** see [endpoint-connection](../api/endpoint-connection.md).

## 2a. Code anchors

| File | What is there |
|---|---|
| `resp/src/commonMain/kotlin/io/github/youndie/kesh/resp/CommandReader.kt` | the incremental parser and `ProtocolException` |
| `resp/src/commonMain/kotlin/io/github/youndie/kesh/resp/ReplyWriter.kt` | the encoder: one growing buffer per connection, so a batch is one write |
| `resp/src/commonMain/kotlin/io/github/youndie/kesh/resp/Reply.kt` | the five reply kinds |
| `resp/src/commonMain/kotlin/io/github/youndie/kesh/resp/InlineArguments.kt` | `sdssplitargs`, ported |
| `resp/src/commonMain/kotlin/io/github/youndie/kesh/resp/RedisNumbers.kt` | `string2ll`, ported; shared with the commands |
| `resp/src/commonTest/kotlin/io/github/youndie/kesh/resp/` | tests, run on the JVM and on linuxX64 |

## 3. How it is built

Incremental: `feed` appends what the socket read, `next` returns the next complete command or `null`
when the bytes end mid-command. A command split across reads and a buffer holding a thousand
pipelined commands are the same code path; `CommandReaderTest` feeds one command cut at every byte
boundary. It is common Kotlin with no I/O and no dependencies, so the tests run on both targets.

Why not a parser that reads from a suspending channel byte by byte: that ties the protocol to one
transport and makes the malformed-input fuzz a network test. `CommandReaderFuzzTest` feeds 20 000
seeded random inputs in random pieces and accepts only a command, `null` or a `ProtocolException`;
its positive control is a mutation that reads a bulk past the buffer, which it catches.

Limits are checked the moment the line that breaks them is complete, so an oversized bulk is refused
from its header, before its data arrives. The unauthenticated limits depend on the caller's
`authenticated` argument, passed per command because `AUTH` changes it mid-pipeline.

A text reply (`Reply.Simple`, `Reply.Error`) refuses CR and LF at construction: the protocol has no
escaping for them, and a line break in an error built from user input would desynchronise the
client.

## 4. Dependencies

None. That is the point of the module.

## 5. Infrastructure and deploy

A module of this build; not published.

## 7. Configuration

None of its own: `RequestLimits` is passed in by `server`, from its configuration.

## 8. Quirks

* **Protocol errors close the connection after the reply**, exactly as Redis does. A client that
  pipelined commands behind a malformed one gets no replies for them.
* **`*0` and `*-1` are skipped**, as Redis skips an empty request, rather than answered.
