---
id: B-07
title: "Lists"
status: wip
priority: P1
size: M
stage: stage-2-types
blocked_by: [B-05]
---

# B-07 — Lists

**Feature:** `feature-lists` — drafted in the *docs/layer-drafts* branch; the `epic` field is added when that document reaches `main`.

Feeds are 300 k lists of 20–200 item ids. Push and pop at both ends, negative indexes, `LTRIM`.

- **The decision and its reason.** A list of packed chunks (Redis's quicklist idea): each chunk one
  `ByteArray` holding several items. It keeps the object count per list proportional to chunks, not
  items, which is research §1.2's lever.
- Rejected: `ArrayDeque<ByteArray>` — one object per item.

- AC: The scenarios of `feature-lists` pass; conformance is green.
- AC: An empty list is deleted.

## Code anchors

| Module | Path |
|---|---|
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/lists/` |
| conformance | `conformance/scripts/lists/` |

Research: [research-architecture](../research/research-architecture.md).
