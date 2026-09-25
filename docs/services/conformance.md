---
id: conformance
title: conformance — the differential harness against real Redis
type: service
status: active
module: conformance
tech_stack: [Kotlin/JVM, raw TCP sockets, Lettuce 7.6.0 (smoke only), redis:7.2]
owner: unassigned
depends_on: [resp, server, redis-server 7.2]
publishes: []
---

# conformance

## 1. Responsibility

Runs the same command scripts against kesh and against a real `redis-server`, over raw sockets, and
compares the replies byte for byte. It is the definition of "compatible" in this repository (research
D-5): a command is done when this harness agrees. It also checks that an unchanged client library
connects (Lettuce, the one the portfolio uses — research §1.1).

**Deliberately does not:** compare through a client library (a client parses replies, so it cannot
compare bytes), record golden files from Redis once (the oracle is run, every time), or time anything
(correctness runs may share a host).

## 2. API contracts

* **Scripts** under `conformance/scripts/<group>/*.redis`, one command per line in `redis-cli`'s own
  quoting, optionally prefixed: `[unordered]`, `[pairs]`, `[shape]`, `[random <population>]`, `[cursor]`, `[info <field>…]`, `[fields <field>…]`, `[frames N]`, `[read N]` and a connection named
by `@name` (normalisers, named on the line they apply
  to), `[closes]` (both servers must close after the reply), `[raw]` (bytes as written, `\r\n` and
  `\xHH` escapes; everything until the server closes or goes quiet is the reply). A header
  `# requires: password` runs the script against the password-protected pair, unauthenticated.
* **The oracle:** the `redis:7.2` image with `conformance/oracle/redis.conf` — `databases 1`, no
  persistence (research D-16). Each run prints the oracle's `redis_version`; 7.2.16 at B-04.
* **A run:** `conformance/run.sh` on the Linux build machine — builds kesh, starts four servers (kesh
  and the oracle, each open and with `requirepass conformance-secret`), runs every script, prints every
  disagreement with both byte streams from the first differing byte, counts comparisons by kind, runs
  the Lettuce smoke, and exits 1 on any disagreement.

## 2a. Code anchors

| File | What is there |
|---|---|
| `conformance/src/jvmMain/kotlin/io/github/youndie/kesh/conformance/Runner.kt` | sending each step to both servers, reading, comparing, reconnecting, the diff |
| `conformance/src/jvmMain/kotlin/io/github/youndie/kesh/conformance/RespFrame.kt` | one reply's exact bytes, with enough structure to normalise |
| `conformance/src/jvmMain/kotlin/io/github/youndie/kesh/conformance/Normaliser.kt` | `EXACT`, `UNORDERED`, `PAIRS`, `SHAPE`, `RANDOM`, `CURSOR` |
| `conformance/src/jvmMain/kotlin/io/github/youndie/kesh/conformance/Script.kt` | the script format |
| `conformance/src/jvmMain/kotlin/io/github/youndie/kesh/conformance/Main.kt` | the run, the oracle's version, the Lettuce smoke |
| `conformance/run.sh` | the four servers and the run |
| `conformance/oracle/redis.conf` | the oracle's configuration |
| `conformance/scripts/connection/` | the connection group: 6 scripts, 78 comparisons at B-04 |
| `conformance/src/jvmTest/kotlin/io/github/youndie/kesh/conformance/HarnessTest.kt` | the harness's own tests, including its positive control |

## 3. How it is built

* **Byte for byte by default; a normaliser is a line a reviewer can refuse.** `[unordered]` compares
  two arrays as multisets; `[pairs]` compares an even array as a multiset of (field, value) pairs —
  a converted hash's `HGETALL`, where `[unordered]` would accept a value moved to another field
  (B-06); `[shape]` compares reply types, array lengths and nulls, and ignores
  content — for what differs by design: identities, `HELLO`'s `server` and `version` (research D-18),
  `CLIENT LIST`. `[random a b c]` checks a random reply (`SPOP`, `SRANDMEMBER`) for membership in the
  population the line names and for Redis's count, holding **both** servers to the population so a
  wrong one fails against Redis too (B-08). `[cursor] SCAN 0 …` is iterated by the runner on each
  server until the cursor returns to 0, and the sorted, de-duplicated unions are compared — pairs
  kept together for `HSCAN` and `ZSCAN` (B-10); an error reply is compared as it came.
  `[info evicted_keys] INFO stats` compares only the named fields' `field:value` lines of a report
  that has many lines kesh does not, and holds the oracle to having each field (B-12).
  `[fields used_memory] INFO memory` compares a report's shape, not its values: every section and
  field kesh prints must be in Redis's, in the same section and order, and the named fields in both
  (B-15). A line may name the connection it uses (`@sub SUBSCRIBE news`, opened when first used)
  and read several replies as one array (`[frames 2] SUBSCRIBE a b`) or read without sending
  (`[read 1] @sub` — what was published to it), with `unordered` for a multiset (B-27). After the
  scripts, besides Lettuce's `PING`, kompot's bus must carry a message between two instances.
* **Each step goes to both servers, one reply at a time.** Replies are read by structure
  (`RespFrame`), so a step's reply is exactly its own bytes; a connection that a step closed is
  reopened on both sides for the next step.
* **Its own positive control, twice.** `HarnessTest` runs two fake servers one byte apart and requires
  the harness to disagree at that byte; and at B-04 a wrong string was planted in kesh (`SELECT 1` →
  `DB index out of range`): the run failed on both `SELECT` lines at byte 14, and passed again once it
  was removed.
* **A reply that cannot be read is a disagreement, not a crash.** The first run died on the oracle's
  RESP3 map (`%`) — see the quirk below — and now such a step is reported and the run goes on.
* **The oracle's configuration is passed as arguments, not mounted.** The image's entrypoint runs
  Redis as its own user, which cannot read a file the sync created with mode 0600; `run.sh` turns each
  line of `redis.conf` into an argument, so the file stays the one source.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Module | [resp](resp.md) | `redis-cli`-style splitting of script lines, and command encoding |
| Module | [server](server.md) | the subject, over TCP |
| External | `redis:7.2` image | the oracle |
| Library | Lettuce 7.6.0 | the smoke that an unchanged client connects |

## 6. Local setup

On the Linux build machine (it needs Docker):

```bash
conformance/run.sh
```

`./gradlew :conformance:jvmTest` runs the harness's own tests and needs no servers.

## 8. Quirks

* **`HELLO 3` is not in any script.** Redis switches the connection to RESP3; kesh refuses by design
  (research D-1). No comparison can pass that, so kesh's side is its own test
  (`ConnectionScenariosTest` "HELLO 3 is refused").
* **`run.sh` waits for kesh to exit.** kore's announce stage keeps kesh's port open for five seconds
  after `SIGTERM`; a script that did not wait started the next run's kesh on a taken port, and the port
  check was answered by the old one.
* **`COMMAND COUNT`, `COMMAND INFO` and `CLIENT LIST` are not compared yet** except by shape where a
  script says so: kesh has 8 commands against Redis's 240, and fills in the rest as the data commands
  land.
