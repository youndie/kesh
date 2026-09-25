---
id: B-12
title: "Eviction policies with sampled LRU"
status: done
priority: P1
size: M
stage: stage-3-memory
epic: feature-memory-limit
blocked_by: [B-11]
---

# B-12 — Eviction policies with sampled LRU

**Feature:** [feature-memory-limit](../features/feature-memory-limit.md).

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
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/config/MemoryConfig.kt` |
| conformance | `conformance/scripts/memory/eviction.redis` |

## Findings (2026-09-25)

- **AC 1 — met.** Five policies, as `evict.c` has them (research D-27). The oracle compares every
  policy at `maxmemory 1`, where each takes all it may before the next command: what is left, which
  writes are refused, `evicted_keys` — 18 scripts, 1 152 comparisons, all agree with Redis 7.2.16.
  Which key a partial eviction takes cannot be compared (the servers account differently) and is
  `EvictionTest`'s: LRU order, a read counting as an access and `EXISTS`/`TYPE`/`TTL` not, the
  nearest expiry, volatile policies leaving persistent keys, the 16-key time check.
- **AC 2 — met, restated.** "Under `maxmemory` plus one sampling batch" is not Redis's bound:
  eviction runs *before* a command, so the excess is what one command writes. A client writing 5 000
  keys under `allkeys-lru` at 64 KiB stayed under the limit plus 4 KiB (read every 250 writes), with more than 4 000
  evictions (`CommandDispatcherTest`); `EvictionTest` holds 20 000 writes to one command plus a
  bucket array.
- **On the way:** `CONFIG SET` takes several parameters now, with `config.c`'s rules — first
  unknown or repeated name wins, a refusal changes nothing. The harness gained `[info <field>…]`
  to compare `INFO` fields by name. `KESH_MAXMEMORY_POLICY` and `KESH_MAXMEMORY_SAMPLES` set both
  at startup.
- **Mutations, each killed by name:** volatile-lru taking any key (oracle, 16 lines; two
  `EvictionTest` cases); no access clock on lookup (`allkeys-lru takes the key accessed longest
  ago`); `EXISTS`/`TYPE`/`TTL` touching (`EXISTS and TYPE and TTL do not count as an access`);
  volatile-ttl order inverted (`volatile-ttl takes the nearest expiry …`); no rounds between
  commands (`KeshServerTest` — eviction that CONFIG SET maxmemory starts goes on between commands);
  `-OOM` only under `noeviction` (oracle, six lines).
- **Not covered:** LFU (refused, research D-27), `maxmemory-eviction-tenacity`, `OBJECT IDLETIME`.

Research: [research-architecture](../research/research-architecture.md).
