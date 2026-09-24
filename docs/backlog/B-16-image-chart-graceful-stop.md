---
id: B-16
title: "Image, chart, and a graceful stop that survives being repeated"
status: open
priority: P1
size: M
stage: stage-5-operations
blocked_by: [B-14, B-15]
---

# B-16 — Image, chart, and a graceful stop that survives being repeated

**Feature:** `feature-operations` — drafted in the *docs/layer-drafts* branch; the `epic` field is added when that document reaches `main`.

kesh deploys like the portfolio's other native services: a minimal image and a Helm chart with one
StatefulSet replica and a persistent volume for snapshots.

- **Grace period and startup budget are derived, not chosen** (research R-5): from B-14's measured
  `SAVE` and load times, with the arithmetic written as a comment next to the values in the chart.
- **The container's memory limit is derived too**: `maxmemory` times B-11's measured ratio, plus the
  drift B-18 will measure (a placeholder until then, labelled as one).
- **The graceful-stop scenario is run repeatedly under load, not once** (research R-3): a signal
  that lands on the selector thread kills the process through `pselect`'s `EINTR`, and one green run
  cannot show that it does not. The number of runs is in the pull request.

- AC: The graceful-stop scenario of `feature-operations` passes in the chart on a test cluster, repeatedly, with 200 connected clients and none receiving a truncated reply.
- AC: The chart's grace period, startup probe budget and memory limit each carry the measurement they were derived from.

## Code anchors

| Module | Path |
|---|---|
| deploy | `deploy/Dockerfile` |
| deploy | `deploy/chart/` |

Research: [research-architecture](../research/research-architecture.md).
