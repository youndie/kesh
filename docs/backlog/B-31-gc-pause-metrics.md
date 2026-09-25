---
id: B-31
title: "Export the collector's pauses on /metrics"
status: wip
priority: P2
size: S
stage: stage-5-operations
epic: feature-operations
---

# B-31 — Export the collector's pauses on /metrics

Issue [#2](https://github.com/youndie/kesh/issues/2). The collector's stop-the-world pause is kesh's
known limitation (research D-3, B-19), and an operator cannot see it: `/metrics` has command latency,
memory and keys, nothing of the collector. The only view is another binary,
`-Pkesh.runtimeLogs=true`. The 24 h soak (B-18) runs the shipped build and so records no pauses.

- **The decision and its reason.** Poll `GC.lastGCInfo` (`@NativeRuntimeApi`, experimental, since
  Kotlin 1.9) from the periodic work, ten times a second, and export each collection once, by its
  `epoch`: pause histograms, a collection count, the last marked-object count, a duration histogram,
  and the epochs missed between two polls — a poll can miss one, and the metric must say so rather
  than look complete.
- **A pause is the runtime's own:** from the request to suspend to the resumption
  (`…PauseRequestTimeNs..…PauseEndTimeNs`), in whole microseconds — what its log calls "Mutators pause
  time", read in `JetBrains/kotlin@v2.4.20!/kotlin-native/runtime/src/gc/common/cpp/GCStatistics.cpp`.
  Deviation from the issue, which proposed `…StartTimeNs..…EndTimeNs`: that leaves out the time the
  mutators already waited for the rest to stop, and would not match the log the control is held to.
- **A collection appears one epoch late.** The runtime keeps a finished epoch as `current` and moves it
  to `last` only when the next one starts (same file, `last = current`); `lastGCInfo` reads `last`.
- For the control: `KESH_GC_LOG=on` prints one line per exported collection, so the metrics' input can
  be compared with the runtime's log epoch by epoch, not only in sum.

- AC: the metrics are in the shipped build, with no compiler flag.
- AC (control): on a `-Pkesh.runtimeLogs=true` build under load, the pauses kesh exports match the
  runtime's `Mutators pause time #1/#2` lines epoch for epoch, and `kesh_gc_epochs_missed_total` is 0 or
  equals the epochs absent from kesh's lines.
- AC: `docs/api/endpoint-http.md` lists the metrics beside the others, the API's experimental status
  stated.

## Code anchors

| Module | Path |
|---|---|
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/info/` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/http/Metrics.kt` |
