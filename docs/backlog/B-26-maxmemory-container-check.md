---
id: B-26
title: "Refuse a maxmemory above the container's memory budget"
status: question
priority: P2
size: S
stage: stage-3-memory
epic: feature-memory-limit
blocked_by: [B-11]
---

# B-26 — Refuse a maxmemory above the container's memory budget

Split out of [B-11](B-11-memory-accounting-and-noeviction.md) by the owner on 2026-09-25. The Kotlin/Native
runtime does not see its container's limit (research §1.2), so a `maxmemory` set above it ends in
an OOM kill rather than in `-OOM` replies. B-11 measured the ratio this check needs — resident
memory 2.8 × `used_memory` at peak (research D-10) — but the budget itself comes from kore's
`containerMemoryBudget()`, which is in kore's `main` (kore #77) and in **no published release**:
kesh pins kore 0.1.4, whose linuxX64 klib has no `MemoryBudget`.

- **The decision and its reason.** Read the budget through kore, not through kesh's own cgroup
  parsing: kore exists so that each service does not carry its own copy of that code. The item
  waits for a kore release rather than duplicating it.
- At startup and on `CONFIG SET maxmemory`, a value that, times the measured peak ratio, exceeds
  the budget is refused with a logged reason naming both numbers; `maxmemory` stays unchanged.

- AC: The "maxmemory beyond the container" scenario of `feature-memory-limit` passes in a container with a memory limit.
- AC: kesh depends on a published kore release that has `containerMemoryBudget()`; the bump is part of this item.

## Code anchors

| Module | Path |
|---|---|
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/config/` |

## Question (for the owner)

The item cannot start until kore publishes a release with `containerMemoryBudget()`; that publish
is the owner's to make, so the item is a `question` rather than `open` — the loop does not pick
it. When the release exists: bump kore in kesh, set the item `open`, and the loop takes it.

Research: [research-architecture](../research/research-architecture.md) §1.2, D-10.
