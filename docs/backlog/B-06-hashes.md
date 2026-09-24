---
id: B-06
title: "Hashes, with a packed encoding for small ones"
status: wip
priority: P1
size: M
stage: stage-2-types
blocked_by: [B-05]
---

# B-06 — Hashes, with a packed encoding for small ones

**Feature:** `feature-hashes` — drafted in the *docs/layer-drafts* branch; the `epic` field is added when that document reaches `main`.

Profiles are 2 M of §5a's keys with 8–20 fields each: stored as a map of `ByteArray` pairs, that is
32–80 M objects for the collector to mark (research §1.2, consequence 1).

- **The decision and its reason.** A small hash is one packed `ByteArray` (field/value pairs in
  sequence, as Redis's listpack), converted to a table past a size threshold. This is the compact
  encoding D-3 allows, and B-19 is expected to show it is what makes D-3 hold. The threshold comes
  from B-19's measurement, not from Redis's default.
- Rejected: always a table — simplest, and multiplies the object count by ~25 for a 12-field hash.

- AC: The scenarios of `feature-hashes` pass; conformance is green, including the conversion boundary in both directions.
- AC: A hash with no fields left is deleted (`EXISTS` answers `:0`).

## Code anchors

| Module | Path |
|---|---|
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/hashes/` |
| conformance | `conformance/scripts/hashes/` |

Research: [research-architecture](../research/research-architecture.md).
