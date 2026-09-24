---
id: B-04
title: "conformance: the differential harness against Redis 7.2, raw bytes with declared normalisers"
status: done
priority: P0
size: M
stage: stage-1-protocol
epic: feature-resp-connection
blocked_by: [B-02]
---

# B-04 — conformance: the differential harness against Redis 7.2, raw bytes with declared normalisers

**Feature:** [feature-resp-connection](../features/feature-resp-connection.md).

"Compatible" is only true where it is checked (brief D-5). The harness runs the same command script
against kesh and against a real `redis-server` and compares the replies.

- **Raw sockets, not a client library** (research D-5). A client library parses the reply — `+OK`
  and `$2\r\nOK\r\n` become the same string — so byte comparison is impossible through one.
  Client libraries get their own smoke tests.
- **Normalisers are declared per command line in the script, never globally**: multiset comparison
  for unordered replies, membership for random ones, the union of a full iteration for cursors,
  shape for clocks and identities. Each is a visible line a reviewer can object to, because a
  normaliser is also a way to hide a real difference.
- **The oracle is Redis 7.2 with `databases 1`** (research D-16): BSD-licensed, complete for §6
  (nothing there is newer than 7.0), still patched. `databases 1` because otherwise `SELECT 1`
  succeeds on the oracle and fails on kesh. The oracle's config file lives next to the harness.
  The owner confirms the pin in this item's review.
- Rejected: golden files recorded once from Redis — a golden rewritten together with the code
  proves nothing, and the point of an oracle is that it is run.

- AC: A script's replies are compared byte for byte, and a mismatch prints both byte streams at the first differing offset.
- AC: A deliberately wrong reply planted in kesh makes the run fail; removing it makes it pass.
- AC: A script line with a normaliser names it; a run lists how many comparisons were normalised and how.
- AC: The oracle's version is printed by the harness from `INFO server` at the start of every run.

## Findings

### Iteration 1 — 2026-09-24, done

Acceptance, `conformance/run.sh` on the Linux build machine:

- **Byte for byte, with the first difference shown:** 6 scripts, 78 comparisons (55 exact, 17 raw,
  6 shape) over every command and refusal kesh has — all agree, three runs in a row.
- **A planted wrong reply fails the run:** `SELECT 1` → `DB index out of range` in kesh; the run
  failed on `SELECT 1` and `SELECT -1` at byte 14, printing both streams; removed, it passed.
  `HarnessTest` makes the same check automatic with two fake servers one byte apart.
- **Normalisers named per line and counted:** the run ends with "78 comparisons: 55 exact, 17 raw, 6
  shape".
- **The oracle's version, every run:** `oracle: redis_version 7.2.16`.
- **An unchanged client:** Lettuce 7.6.0 with defaults connects to kesh and gets `PONG`.
- 9 harness tests, `ktlintCheck` clean. Mutations caught: exact comparison always true; `[closes]`
  without its claim (found as a real defect first — below).

Found on the way:

- **`HELLO 3` cannot be compared**: Redis answers a RESP3 map and switches; kesh refuses by design.
  Left out of the scripts; the harness now reports an unreadable reply instead of dying on it.
- **`[closes]` was weaker than it read**: two servers that both stayed open agreed on it. It now
  requires both to close.
- **The oracle cannot read a mounted config** synced with mode 0600 (the image runs Redis as its own
  user); `run.sh` passes the file's lines as arguments.
- **`run.sh` must wait for kesh to exit**: kore's 5 s announce stage kept the port, and the next run's
  kesh died on `EADDRINUSE` while the old one answered the port check.
- **A `sed` mutation that does not apply looks like a survived mutant**: twice now the formatter had
  moved the pattern. Recorded in `CLAUDE.md`: check `git diff` before reading the run.

Left open: the owner's confirmation of the Redis 7.2 pin (research D-16). The `random` and `cursor`
normalisers arrive with the commands that need them (B-08, B-10).

## Code anchors

| Module | Path |
|---|---|
| conformance | `conformance/src/jvmMain/kotlin/io/github/youndie/kesh/conformance/Runner.kt` |
| conformance | `conformance/run.sh` |
| conformance | `conformance/oracle/redis.conf` |
| conformance | `conformance/scripts/connection/` |

Research: [research-architecture](../research/research-architecture.md).
