---
id: B-04
title: "conformance: the differential harness against Redis 7.2, raw bytes with declared normalisers"
status: open
priority: P0
size: M
stage: stage-1-protocol
blocked_by: [B-02]
---

# B-04 — conformance: the differential harness against Redis 7.2, raw bytes with declared normalisers

**Feature:** `feature-resp-connection` — drafted in the *docs/layer-drafts* branch; the `epic` field is added when that document reaches `main`.

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

## Code anchors

| Module | Path |
|---|---|
| conformance | `conformance/src/` |
| conformance | `conformance/oracle/redis.conf` |
| conformance | `conformance/scripts/` |

Research: [research-architecture](../research/research-architecture.md).
