---
id: B-13
title: "Active expiry, Redis's algorithm as its source has it"
status: open
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

Research: [research-architecture](../research/research-architecture.md).
