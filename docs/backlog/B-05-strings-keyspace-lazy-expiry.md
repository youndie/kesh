---
id: B-05
title: "Strings and keyspace on kesh's own hash table, lazy expiry"
status: done
priority: P0
size: M
stage: stage-1-protocol
epic: feature-strings
blocked_by: [B-02, B-04, B-19]
---

# B-05 — Strings and keyspace on kesh's own hash table, lazy expiry

**Feature:** [feature-strings](../features/feature-strings.md), [feature-keyspace](../features/feature-keyspace.md).

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
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/` |
| store | `store/src/linuxX64Test/kotlin/io/github/youndie/kesh/store/KeyspaceGrowthTest.kt` |
| conformance | `conformance/scripts/strings/` |

## Findings (2026-09-25)

- **Acceptance.** The scenarios of both features are automated (`CommandsTest`, the server's
  connection tests); `conformance/run.sh` compares 272 replies with Redis 7.2, all agreeing, and a
  `GETRANGE` mutant the first scripts missed is now caught. `DECRBY k -9223372036854775808` answers
  `-ERR decrement would overflow` on both.
- **The bound at sixteen million keys is asserted on the table**: `KeyspaceGrowthTest` (linuxX64,
  60 s, ~2 GB) inserts 16 M keys and checks `bucketsMovedLastStep` after every one; reporting one
  bucket more than moved fails it.
- **The slowest `SET` of a 16 M growth is at least 3 s, and the table is not why.** `redis-benchmark
  -t set -n 16000000 -r 2000000000 -P 16 -c 50` against kesh on the build machine: 15 932 752 keys in
  161.9 s, p50 2.26 ms, p99 5.55 ms; 99.7 % under 21 ms, 0.1 % over 1.6 s, and 11 200 requests at
  `redis-benchmark`'s ceiling — it records anything slower than 3 s as 3 s
  (`redis/redis@7.2.5!/src/redis-benchmark.c` line 568), so "max 3000.319 ms" means "3 s or more".
  Resident memory 5.1 GB. The stalls are the collector's **mutator assists**: once allocation
  reaches the heap target, the runtime stops every Kotlin thread until the running mark finishes,
  and a mark of 12–27 M live objects took 1.6–4.2 s by the runtime's clock (research §1.2,
  correction found in B-05). Recorded as a limitation with a lever, not fixed here: B-23.
- **Two numbers from the first runs were not kesh.** A run with `-r 1000000000000` wrote one key
  (`redis-benchmark` reads `-r` with `atoi`). And the "max 2936.831 ms" of a single-key run, where
  the heap never grows, is the build machine's wall clock stepping +2.93 s every ~32 s — Redis on the
  same host shows the same maximum over a long enough run (the bench service's quirks).

Research: [research-architecture](../research/research-architecture.md).
