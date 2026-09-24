---
id: B-09
title: "Sorted sets with logarithmic rank and range"
status: open
priority: P1
size: L
stage: stage-2-types
blocked_by: [B-05]
---

# B-09 — Sorted sets with logarithmic rank and range

**Feature:** `feature-sorted-sets` — drafted in the *docs/layer-drafts* branch; the `epic` field is added when that document reaches `main`.

Leaderboards: 2 000 sorted sets of 1 000–100 000 members, ordered by (score, member bytes), with rank
and range queries logarithmic in the set's size.

- **The decision and its reason.** An order-statistic structure (a skiplist with spans, as Redis, or
  a balanced tree with subtree counts) plus a member → score index. The choice is the implementer's
  and is recorded in the feature document with its object count per member, because at ~10–20 M
  members (research §1.2) that count matters as much as the complexity.
- Small sorted sets packed, as B-06.

- AC: The scenarios of `feature-sorted-sets` pass; conformance is green, including score ties ordered by member bytes.
- AC: `ZRANK` on a 1 M-member set takes within 10× the time it takes on a 1 k-member one (a relative assertion, not an absolute one).

## Code anchors

| Module | Path |
|---|---|
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/zsets/` |
| conformance | `conformance/scripts/zsets/` |

Research: [research-architecture](../research/research-architecture.md).
