---
id: B-08
title: "Sets"
status: wip
priority: P1
size: S
stage: stage-2-types
blocked_by: [B-05]
---

# B-08 — Sets

**Feature:** `feature-sets` — drafted in the *docs/layer-drafts* branch; the `epic` field is added when that document reaches `main`.

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
| conformance | `conformance/scripts/sets/` |

Research: [research-architecture](../research/research-architecture.md).
