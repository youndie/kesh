---
id: B-13
title: "Active expiry, Redis's algorithm as its source has it"
status: done
priority: P1
size: S
stage: stage-3-memory
epic: feature-keyspace
blocked_by: [B-05]
---

# B-13 — Active expiry, Redis's algorithm as its source has it

**Feature:** [feature-keyspace](../features/feature-keyspace.md).

Keys that are never read again must still go (30 % of sessions and every rate counter in §5a).

- **The decision and its reason** (research §1.5, corrected D-11): 10 cycles a second; each samples
  20 keys with an expiry per loop and repeats while more than **10 %** of a sample is expired; the
  slow cycle is bounded to **25 % of CPU** time. The brief's "25 %" was the CPU budget, not the
  repeat threshold.
- Runs on the store thread (research D-14), in slices, so that it never holds a command for longer
  than its slice.

- AC: The active-expiry scenario of `feature-keyspace` passes: of 100 000 `PX 50` keys never read again, at least 99 % are gone within 2 s.
- AC: It passes on the reference dataset's TTL keys (B-03), and `expired_keys` in `INFO stats` accounts for them.

## Code anchors

| Module | Path |
|---|---|
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/expiry/` |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/Db.kt` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/KeshServer.kt` |
| bench | `bench/src/commonTest/kotlin/io/github/youndie/kesh/bench/DatasetExpiryTest.kt` |

## Findings (2026-09-25)

- **Built on B-11's branch, and merges after it.** The second criterion reads `expired_keys` from
  `INFO`, which exists only there; the expiry index is also counted in B-11's `used_memory`.
- **AC 1 — met.** `ActiveExpiryTest`: 100 000 `PX 50` keys, twenty cycles a clock-moved 100 ms
  apart, at least 99 % gone, on the JVM and linuxX64. Through the running server
  (`redis-cli --pipe`, a real 2 s): all 100 000 were gone before the 0.76 s load had finished,
  `expired_keys:100000`, one cycle at the time cap.
- **AC 2 — met.** `DatasetExpiryTest` (bench, both targets): the reference dataset at 1/256, the
  clock moved a day on — every TTL key (sessions' 30 %, all rate counters) deleted by active expiry,
  `expired_keys` equal to their number, every other key kept.
- **What Redis's algorithm needed that its source did not show: shrinking tables.** On linuxX64 the
  first version stopped at 1 292 keys for good — the cycle skips an index under 1 % full, and kesh's
  never shrank. The periodic work now shrinks the keyspace and the index under 10 % full and rehashes
  16 384 buckets a call (research D-11); a `SCAN` across a shrink is tested (`ScanTest`).
- **Left out:** Redis's fast expiry cycle (no "before sleep" in a coroutine server); shrinking a
  collection's own table.
- **Mutations, each caught:** a cycle that never repeats its loop (`ActiveExpiryTest`); an expiry
  cleared without leaving the index (`ActiveExpiryTest`, `MemoryAccountingTest`'s index check).
- **Found on the way:** a second kesh on a taken port aborts with a core dump — B-24.

Research: [research-architecture](../research/research-architecture.md).
