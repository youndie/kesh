---
id: B-08
title: "Sets"
status: done
priority: P1
size: S
stage: stage-2-types
epic: feature-sets
blocked_by: [B-05]
---

# B-08 — Sets

**Feature:** [feature-sets](../features/feature-sets.md).

Tags: 500 k small sets of 3–15 short members.

- **The decision and its reason.** Small sets packed like small hashes (B-06), converted to a table
  past a threshold; integer-only sets may use a packed integer encoding as Redis's intset does, if
  B-19 shows the object count matters at this size.
- Conformance needs the multiset normaliser (research D-5) for `SMEMBERS`, `SINTER`, `SUNION`,
  `SDIFF`, and membership checks for `SPOP`/`SRANDMEMBER`.

- AC: The scenarios of `feature-sets` pass; conformance is green with the normalisers named per line.
- AC: An empty set is deleted.

## Code anchors

| Module | Path |
|---|---|
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/sets/` |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/SetCommands.kt` |
| conformance | `conformance/scripts/sets/` |

## Findings (2026-09-25)

- **Packed under Redis's listpack limits for sets, 128 members of 64 bytes; no intset** (research
  D-22). B-19 showed the object count does not drive the pause, so the intset's case — integer
  sets — did not earn a third encoding; its only visible effect in Redis, sorted `SMEMBERS`, is an
  order Redis does not promise.
- **The `random` normaliser research D-5 planned is built**: `[random <population>]` holds both
  replies to the named members and kesh's to Redis's count and distinctness. `HarnessTest` covers it;
  a population written wrong fails against Redis, which is its own check.
- **Acceptance.** Both scenarios automated (`SetCommandsTest`), the boundary in `SetValueTest`;
  `conformance/run.sh`: 14 scripts, 675 comparisons, all agree with Redis 7.2.16, 133 new — 18
  `[unordered]` and 8 `[random]` among them.
- **Mutations, each caught by the oracle and by the unit tests:** `SPOP` not removing the member it
  returns; `SINTER` stopping at a missing key before type-checking the rest; a negative
  `SRANDMEMBER` count one short (caught through `[random]`).
- **On the way:** `StoreCommands.all` now registers every group once — the dispatcher's list had
  outgrown a line.

Research: [research-architecture](../research/research-architecture.md).
