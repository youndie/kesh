---
id: B-11
title: "Memory accounting, maxmemory and noeviction, checked against the container's limit"
status: open
priority: P1
size: M
stage: stage-3-memory
blocked_by: [B-10]
---

# B-11 — Memory accounting, maxmemory and noeviction, checked against the container's limit

**Feature:** `feature-memory-limit` — drafted in the *docs/layer-drafts* branch; the `epic` field is added when that document reaches `main`.

The Kotlin/Native runtime neither enforces a heap ceiling of the kind `maxmemory` implies nor sees
its container's limit (research §1.2). kesh's own accounting (D-10) is the only thing between a
write and an OOM kill.

- **The decision and its reason.** Every value kind reports its estimated size on every mutation;
  `used_memory` is their sum. At startup and on `CONFIG SET maxmemory`, kore's
  `containerMemoryBudget()` is read and a `maxmemory` that, with the overhead ratio this item
  measures, exceeds the budget is refused with a logged reason.
- Under `noeviction`, a write that would exceed the limit answers
  `-OOM command not allowed when used memory > 'maxmemory'.`; reads and deletes still work.
- **The measurement is the other half of the item**: resident memory against `used_memory` on the
  reference dataset (B-03), written into research D-10 with how it was taken.

- AC: The "refuse when full" scenario of `feature-memory-limit` passes.
- AC: The ratio of resident memory to `used_memory` on the reference dataset is measured, and research D-10 says what it is and how it was measured.
- AC: `CONFIG SET maxmemory` above the container's budget is refused, and the refusal names both numbers.

## Code anchors

| Module | Path |
|---|---|
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/memory/` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/config/` |

Research: [research-architecture](../research/research-architecture.md).
