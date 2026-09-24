---
id: B-05
title: "Strings and keyspace on kesh's own hash table, lazy expiry"
status: open
priority: P0
size: M
stage: stage-1-protocol
blocked_by: [B-02, B-04, B-19]
---

# B-05 — Strings and keyspace on kesh's own hash table, lazy expiry

**Feature:** `feature-strings`, `feature-keyspace` — drafted in the *docs/layer-drafts* branch; the `epic` field is added when that document reaches `main`.

The first real data. It is also where the keyspace's data structure is chosen, and that choice is
hard to undo.

- **The keyspace is kesh's own table with incremental rehashing** (research §1.3, D-12). The stdlib
  `HashMap` on Native rehashes every entry inside the call that crosses the threshold — at 8 M keys,
  inside one `SET` — and exposes no buckets, so `SCAN` (B-10) could not be built on it. Keys hash by
  content: a `ByteArray` compares by identity.
- **`DEL`, `UNLINK`, `FLUSHALL [ASYNC]` differ only in accounting** (research D-15): all remove before
  replying and subtract from `used_memory`; memory returns when the collector sweeps.
- **Blocked by B-19** (research D-17): the keyspace is not built on plain Kotlin objects until the heap
  probe has said whether they can hold the reference dataset.
- Also: `SET` options per the brief, `INCR` family with both overflow strings (`increment or
  decrement would overflow`, and `decrement would overflow` for `DECRBY` of `LLONG_MIN`).

- AC: The scenarios of `feature-strings` and the lazy-expiry scenarios of `feature-keyspace` pass; conformance is green for their command scripts.
- AC: Growing the keyspace from 0 to 16 M keys, no single command moves more than a bounded number of buckets (asserted on the table, not on a timer), and the slowest `SET` of the run is reported.
- AC: `DECRBY k -9223372036854775808` answers `-ERR decrement would overflow`, as the oracle does.

## Code anchors

| Module | Path |
|---|---|
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/keyspace/` |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/strings/` |
| conformance | `conformance/scripts/strings/` |

Research: [research-architecture](../research/research-architecture.md).
