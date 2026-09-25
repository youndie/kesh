---
id: B-29
title: "Budget a background save in the container's memory"
status: question
priority: P2
size: S
stage: stage-4-persistence
epic: feature-snapshots
blocked_by: [B-25]
---

# B-29 — Budget a background save in the container's memory

Found in B-25 (`bench/reports/b-25/`). At 1/8 of the reference dataset under the reference load,
`used_memory` 1.23 GB, the server alone is resident at 3.2–4.4 GB (B-28's ratio, which the chart's
limit is sized from: `residentPeakRatioTenths: 33`). A `BGSAVE` adds its child to the same cgroup:
the two together peaked at **5.7 GB proportional (Pss) — 4.6 × `used_memory`**, with the allocation-free
writer. The child's own allocations are small now; what it holds is the pages the parent rewrote
during the save — copy-on-write, driven by the load's writes and by the collector: its mark is a
compare-and-set on each live object's `ObjectData`, and its sweep resets it, so one epoch in the
parent writes to every page that holds a live object
(`JetBrains/kotlin@v2.4.20!/kotlin-native/runtime/src/gc/pmcs/cpp/ObjectData.hpp`, `tryMark`,
`tryResetMark`; the same in `gc/cms`). In a pod at the chart's limit, a `BGSAVE` under load
crosses it, and the OOM killer takes whichever process is largest at that moment. On the build
machine, with the old writer, it took the child (4.35 GB resident against the parent's 3.85 GB); with
the copies gone the child holds less than the parent, so the next time it may well be the server.

Nothing in kesh issues a `BGSAVE` on its own: the save on stop is a `SAVE` (B-16), and there is no
`save <seconds> <changes>` schedule. The risk is an operator's or a client's `BGSAVE`.

The choices, for the owner:

1. **Size the chart's limit for it:** a ratio of about 4.6 in place of 3.3 — some 40 % more memory
   requested for every pod, for a command nothing issues by default.
2. **Refuse a `BGSAVE` the budget cannot hold:** with kore's `containerMemoryBudget()` (B-26's
   dependency), answer `-ERR` when resident memory plus an estimate of the child (the measured 1.4 ×
   `used_memory`) exceeds the budget. Not Redis's behaviour — Redis forks and lets the kernel decide —
   and the estimate is a measurement at one scale on one host.
3. **Document it and leave it:** the feature's quirk and the chart's comment say that a `BGSAVE`
   under load needs about 1.4 × `used_memory` beyond the limit's ratio.

- AC: the owner's choice is recorded in `backlog.md`'s decisions, and whatever it asks for is built
  and measured in a container with a memory limit.

## Code anchors

| Module | Path |
|---|---|
| deploy | `deploy/chart/values.yaml` — `measured.residentPeakRatioTenths` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/persistence/Persistence.kt` |
| bench | `bench/fork/bgsave.sh` |
