---
id: B-12
title: "Eviction policies with sampled LRU"
status: wip
priority: P1
size: M
stage: stage-3-memory
blocked_by: [B-11]
---

# B-12 — Eviction policies with sampled LRU

**Feature:** `feature-memory-limit` — drafted in the *docs/layer-drafts* branch; the `epic` field is added when that document reaches `main`.

`allkeys-lru`, `volatile-lru`, `allkeys-random`, `volatile-random`, `volatile-ttl`, with LRU
approximated by sampling `maxmemory-samples` keys, as Redis does.

- **The decision and its reason.** An access clock per key (a few bits, not a timestamp object) and
  sampling from kesh's table. Eviction frees in accounting at once and in the heap at the next sweep
  (research D-15), so the policy works against `used_memory`, not resident memory.

- AC: The "evict when full" scenario of `feature-memory-limit` passes for every policy.
- AC: `evicted_keys` in `INFO stats` grows, and `used_memory` stays under `maxmemory` plus one sampling batch.

## Code anchors

| Module | Path |
|---|---|
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/eviction/` |

Research: [research-architecture](../research/research-architecture.md).
