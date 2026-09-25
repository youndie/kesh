---
id: B-11
title: "Memory accounting, maxmemory and noeviction, checked against the container's limit"
status: question
priority: P1
size: M
stage: stage-3-memory
epic: feature-memory-limit
blocked_by: [B-10]
---

# B-11 — Memory accounting, maxmemory and noeviction, checked against the container's limit

**Feature:** [feature-memory-limit](../features/feature-memory-limit.md).

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
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/Db.kt` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/config/` |
| bench | `bench/memory/ratio.sh` |

## Findings (2026-09-25)

- **AC 1, refuse when full — met.** `used_memory` is a running total settled after each write
  command (research D-10); the 23 commands Redis flags `denyoom` are refused over `maxmemory`.
  `CommandDispatcherTest` fills 20 KiB and checks the refusal, a `GET` and a `DEL`; the oracle
  compares, at `maxmemory 1`, every refused and allowed command and `CONFIG`'s replies:
  17 scripts, 1 051 comparisons, all agree with Redis 7.2.16 — after one fix: `CONFIG GET` answers
  under the name as the client first wrote it (`MaxMemory`), once whatever its case.
  `MemoryAccountingTest` holds the total to a recount through 20 000 random commands.
- **AC 2, the ratio — met** at 1/16 and 1/8: resident memory 2.4 × `used_memory` idle, 2.8 × at
  peak (`bench/reports/b-11/README.md`, research D-10). Full scale waits for B-22's host.
- **AC 3, the container check — not built: its premise does not hold.** The item names kore's
  `containerMemoryBudget()`. It exists in kore's `main` since 2026-09-15 (kore #77) but in **no
  published release**: kesh pins kore 0.1.4, released 2026-09-12, and its linuxX64 klib has no
  `MemoryBudget` (checked in the artefact). Maven Central and the portfolio's repository list 0.1.4
  as the latest.
- **Mutations, each caught:** the `denyoom` check removed (the oracle's `OOM` lines); a hash's value
  replacement not accounted (`MemoryAccountingTest`).
- **On the way:** `CONFIG GET`/`SET maxmemory` and `INFO memory` exist now, ahead of B-15, because
  the ratio could not be read without them.

## Question (for the owner)

The container check needs `containerMemoryBudget()`, which no kore release has. Options:

1. **Release kore** (0.1.5, with #77) and bump kesh to it; the check lands in B-11 as written. A
   publish of a library of yours — your call to make.
2. **Split the check out** into a new item blocked on that release, and close B-11 with ACs 1 and 2.
   B-12 (eviction), blocked by B-11, can then go ahead now.
3. **Read the cgroup in kesh** until kore is released — duplicating kore's code, which is the thing
   kore exists to avoid.

Research's recommendation: **2**, and 1 whenever kore is next released. Until then B-11 is not
merged; its branch `feat/b-11-memory-accounting` holds everything above.

Research: [research-architecture](../research/research-architecture.md).
