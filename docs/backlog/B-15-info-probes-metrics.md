---
id: B-15
title: "INFO, HTTP probes and Prometheus metrics"
status: open
priority: P1
size: M
stage: stage-5-operations
blocked_by: [B-11]
---

# B-15 — INFO, HTTP probes and Prometheus metrics

**Feature:** `feature-operations` — drafted in the *docs/layer-drafts* branch; the `epic` field is added when that document reaches `main`.

Operators see what the instance is doing through `INFO`, the HTTP port and the metrics.

- **The decision and its reason.** `INFO` sections `server`, `clients`, `memory`, `persistence`,
  `stats`, `keyspace` with Redis's field names where the meaning is the same — including
  `connected_clients` and `rejected_connections`, which make the connection ceiling (research D-13)
  visible before it hurts. Liveness and readiness through kore's gates on port 8080; readiness is
  false until the snapshot has loaded.
- Metrics include resident memory **and** `used_memory` side by side, and the thread count, because
  their ratio is what research R-2 watches.

- AC: `INFO` fields as `feature-operations` lists them; conformance checks the shape of `INFO`, not its values.
- AC: A test scrapes `/metrics` and parses it as Prometheus text.
- AC: Readiness is false during a snapshot load and true after it.

## Code anchors

| Module | Path |
|---|---|
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/http/` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/info/` |

Research: [research-architecture](../research/research-architecture.md).
