---
id: B-06
title: "Hashes, with a packed encoding for small ones"
status: done
priority: P1
size: M
stage: stage-2-types
epic: feature-hashes
blocked_by: [B-05]
---

# B-06 — Hashes, with a packed encoding for small ones

**Feature:** [feature-hashes](../features/feature-hashes.md).

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
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/HashCommands.kt` |
| conformance | `conformance/scripts/hashes/` |

## Findings (2026-09-25)

- **The threshold did not come from B-19, because B-19 could not give one.** It packed every hash and
  found that the pause does not follow the object count; packing stays for memory. The threshold is
  Redis 7.2's own rule and defaults — 512 fields, 64-byte fields and values, one-way — chosen so that
  a packed hash answers in the same order in both servers (research D-20). The Redis defaults are
  512 and 64 in `config.c` of 7.2.5.
- **Acceptance.** The three scenarios are automated (`HashCommandsTest`, `HashValueTest`, and
  `conformance/scripts/hashes/encoding.redis`); `conformance/run.sh`: 12 scripts, 400 comparisons,
  all agree with Redis 7.2.16 — 128 of them new, six under the new `[pairs]` normaliser. `EXISTS`
  after the last `HDEL` answers `:0` on both.
- **Mutations, each caught:** converting at 512 fields instead of 513 (three exact lines of the
  oracle, and `HashValueTest`); not converting on a 65-byte value (`HashValueTest` — the oracle cannot
  see it: a packed hash in insertion order still passes `[pairs]` against a table); leaving an empty
  hash behind (the oracle's `EXISTS`, and `HashCommandsTest`).
- **Out of the brief's list, so not built:** `HSETNX`, `HMSET`, `HSTRLEN`, `HRANDFIELD`.

Research: [research-architecture](../research/research-architecture.md).
