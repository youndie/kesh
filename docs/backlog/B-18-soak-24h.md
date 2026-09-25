---
id: B-18
title: "24 h soak with TTL churn"
status: wip
priority: P2
size: S
stage: stage-6-capacity
epic: feature-operations
blocked_by: [B-17]
---

# B-18 — 24 h soak with TTL churn

**Feature:** [feature-operations](../features/feature-operations.md).

A non-moving collector cannot compact (research R-2); over a day of TTL churn, resident memory may
drift away from `used_memory` even though nothing leaks.

- 24 h at the reference load, with resident memory, `used_memory`, thread count and latency
  percentiles per hour. The ratio of resident to `used_memory` per hour is the number B-16's memory
  limit needs.

- AC: No crash, and a per-hour table of resident memory, `used_memory`, their ratio and latency percentiles.
- AC: Research R-2 says whether the drift is bounded, with the table as evidence.

## Code anchors

| Module | Path |
|---|---|
| bench | `bench/soak/` |
| bench | `bench/reports/` |

Research: [research-architecture](../research/research-architecture.md).
