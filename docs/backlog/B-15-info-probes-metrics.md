---
id: B-15
title: "INFO, HTTP probes and Prometheus metrics"
status: done
priority: P1
size: M
stage: stage-5-operations
epic: feature-operations
blocked_by: [B-11]
---

# B-15 — INFO, HTTP probes and Prometheus metrics

**Feature:** [feature-operations](../features/feature-operations.md).

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

## Findings (2026-09-25)

- **AC 1 — met.** `INFO` has the six sections in Redis's order, with only the fields whose meaning is
  Redis's (research D-28). `conformance/scripts/server/info.redis` holds every section and field kesh
  prints to Redis 7.2's report with a new normaliser, `[fields]`: 19 scripts, 1 172 comparisons, all
  agree. `avg_ttl` is `activeExpireCycle`'s running average (`ActiveExpiryTest`).
- **AC 2 — met.** `HttpPortTest` scrapes `/metrics` and parses it with a strict reader of the text
  format; Prometheus's own `promtool check metrics` passes a scrape of the release binary
  (`bench/http/probe.sh`, on the build machine).
- **AC 3 — met.** `HttpPortTest` loads a 300 000-key snapshot and probes throughout: 503 naming the
  snapshot until the load ends, then 200, never back. Readiness also goes 503 when `SIGTERM` starts
  kore's plan (the announce stage, `Main.kt`): seen on the release binary, with liveness 200 and exit 0.
- **Found on the way:** the announce stage dwells 5 s before the drain starts (kore's default
  `preDrainWait`) — B-16's grace period starts from it. The HTTP port takes one descriptor of the
  ceiling's reserve (`maxclients` 986 → 985 on the build machine). Every launcher inherits the 8080
  default, so the conformance and bench scripts now start kesh with `KESH_HTTP_PORT=off`.
- **Mutations, each killed by name:** a field Redis lacks (oracle, 5 lines); ready before the load
  (`readiness is false while the snapshot loads …`); readiness ignoring the shutdown latch (`the
  three probes answer 200 …`); commands not recorded (`metrics parse as Prometheus text …`); the
  `+Inf` bucket left out — survived the server test, where no command takes a second, and was killed
  by `MetricsTest` added for it; `avg_ttl` without its running average (`ActiveExpiryTest`).
- **Not covered:** `SHUTDOWN`, `TIME`, `MEMORY USAGE`, `DEBUG SLEEP` and `CONFIG` for `requirepass`
  and `save-on-shutdown` stay *target* in `endpoint-server`; the item did not name them.

Research: [research-architecture](../research/research-architecture.md).
