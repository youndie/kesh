---
id: B-26
title: "Refuse a maxmemory above the container's memory budget"
status: done
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
`containerMemoryBudget()`, which is in kore's `main` (kore #77) and in no release until 0.1.5
(below); kesh pins kore 0.1.4, whose linuxX64 klib has no `MemoryBudget`.

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
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/config/MemoryBudgetCheck.kt` — the check |
| server | `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/config/MemoryBudgetTest.kt` |
| deploy | `deploy/chart/templates/statefulset.yaml` — `KESH_RESIDENT_PEAK_RATIO_TENTHS` |

## Unblocked (2026-09-25)

kore 0.1.5 was released on 2026-09-25 to the portfolio's repository, not to Maven Central, with
`containerMemoryBudget()` in its `kore-core-linuxx64` klib. The owner's condition for this item is
met, so it is `open`; the bump from 0.1.4 (`gradle/libs.versions.toml`) is its first step.

Research: [research-architecture](../research/research-architecture.md) §1.2, D-10.

## Findings — 2026-09-25: done

- **The ratio is B-28's 3.3, not B-11's 2.8** the item named: B-11's was taken without traffic, and the
  chart has sized its limit at 3.3 since B-28. So that the two cannot disagree, the chart passes its
  own value (`KESH_RESIDENT_PEAK_RATIO_TENTHS`, from `measured.residentPeakRatioTenths`); 33 is the
  default for a binary run without the chart.
- `maxmemory 0` and a budget that is unbounded or unreadable refuse nothing — kesh cannot say more
  than kore reads — and the startup log names which of the three it was.
- **AC 1 — met.** In a real container with a memory limit (the release binary in
  `gcr.io/distroless/cc-debian13`, the image's runtime base, `docker run --memory 1g`, on the build
  machine): the log reads `memory budget 1 GiB (/sys/fs/cgroup/memory.max)`; `CONFIG SET maxmemory
  2gb` answers `-ERR CONFIG SET failed (possibly related to argument 'maxmemory') - maxmemory 2147483648
  (2.00G) needs up to 3.3 x as much resident memory, more than the container's limit of 1073741824
  (1.00G, /sys/fs/cgroup/memory.max); at most 325376310 (310.30M) fits`; `100mb` is accepted and
  `CONFIG GET` shows it; a start with `KESH_MAXMEMORY=2gb` exits 1 with the same reason; without
  `--memory` the log reads `memory budget no limit`. In the suite: `MemoryBudgetTest`, five tests; the
  two checks each removed by a mutant, each caught by the test named for it.
- **AC 2 — met.** kore 0.1.5 from the portfolio's repository; `containerMemoryBudget()` and `render()`
  resolve in its linuxX64 klib (kesh compiles against them).
