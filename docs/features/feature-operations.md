---
id: feature-operations
title: Observability and deployment
type: feature
status: active
owner: unassigned
involved_services: [server, bench, conformance]
client_entries: []
api: [endpoint-server, endpoint-http]
tags: [operations]
---

# Observability and deployment

## 1. Overview

Operators see what the instance is doing and run it like every other native service in the
portfolio: one image, one chart, probes, metrics, a memory limit, an ordered stop.

Built in B-15: `INFO`, the probes and the metrics. *Target*: the image, the chart and the graceful
stop under load (B-16, with the `deploy` module), the reference load report and the soak (B-17,
B-18).

## 2. Business rules

* `INFO` has sections `server`, `clients`, `memory`, `persistence`, `stats` and `keyspace`, with
  Redis's field names **only where the field means the same thing** — `connected_clients` and
  `rejected_connections` make the connection ceiling visible (research D-13); `used_memory_rss`
  stands beside `used_memory`. A field kesh cannot fill truthfully is absent, not 0 (research D-28).
* An HTTP port (`KESH_HTTP_PORT`, 8080) serves kore's three probes — `/health/live`,
  `/health/started`, `/health/ready` — and `/metrics` in Prometheus text: a latency histogram by
  command (its `_count` gives commands per second), `used_memory`, keys, expired and evicted keys,
  connected clients, **resident memory and thread count** — the ratio of resident memory to
  `used_memory` is what research R-2 watches ([endpoint-http](../api/endpoint-http.md)).
* **Readiness is false until the snapshot has loaded and the RESP listener is bound**, and false
  again from the moment `SIGTERM` starts kore's plan; liveness stays true through both.
* *Target* (B-16): `SIGTERM` stops accepting connections, finishes the commands in flight, saves if
  configured, and exits within the chart's grace period — which is derived from B-14's measured
  `SAVE` time, not chosen (research R-5).
* *Target* (B-16, B-18): the chart's memory limit is derived from `maxmemory` and the measured
  overhead (B-11, B-18); the runtime cannot see it (research §1.2).

## 3. Flow

`SIGTERM` → kore's plan: announce (readiness false, built in B-15; then kore's 5 s dwell) → kesh's own drain participant for the RESP
listener (stop accepting, let queued commands finish and replies flush — kore's `EngineDrain` is for
Ktor engines, and the RESP listener is not one) → save if `save-on-shutdown` → release → exit.

## 4. Code anchors

| Service | Code |
|---|---|
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/info/` — `INFO`, the per-command stats, the process's own numbers |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/http/` — the HTTP port and the exposition |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/KeshServer.kt` — the gates, the routes, the order of the start |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/Main.kt` — kore's plan: announce, then drain |
| conformance | `conformance/scripts/server/info.redis` — `INFO`'s shape against Redis 7.2 |
| bench | `bench/http/probe.sh` — the release binary's port: `promtool`, and readiness across `SIGTERM` |

*Target*: the chart in a `deploy` module (B-16); the load profiles and the soak under `bench` (B-17,
B-18), reported beside the existing `bench/reports/`.

## 5. Scenarios

### Scenario: Ready after load
* **Given:** a snapshot (300 000 keys in the test; the reference dataset in B-16's chart)
* **When:** the server starts
* **Then:** readiness fails until the load completes and passes after it
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/http/HttpPortTest.kt::readiness is false while the snapshot loads and true after it`

### Scenario: Not ready once the stop begins
* **Given:** a started server
* **When:** `SIGTERM`
* **Then:** readiness answers 503 `not ready: shutting down` through kore's announce stage, and liveness 200
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/http/HttpPortTest.kt::the three probes answer 200 once started and readiness falls when the announce stage runs` — the gate; through the release binary and a real `SIGTERM`: `bench/http/probe.sh`

### Scenario: Graceful stop, repeatedly
*Target*, B-16.
* **Given:** 200 connected clients under load
* **When:** `SIGTERM`, repeated over many runs
* **Then:** no run crashes, and no client receives a truncated reply

### Scenario: Metrics parse
* **When:** a test scrapes `GET /metrics`
* **Then:** the body parses as Prometheus text and carries `used_memory`, resident memory and thread count
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/http/HttpPortTest.kt::metrics parse as Prometheus text and carry memory threads and the command histogram` — with a strict reader of the format; Prometheus's own `promtool check metrics` in `bench/http/probe.sh`

### Scenario: INFO has Redis's shape
* **When:** `INFO`, whole or by section
* **Then:** every section and field kesh prints is in Redis 7.2's report, in its section and order
* **Automated:** `conformance/scripts/server/info.redis`

### Scenario: Reference load reported
*Target*, B-17.
* **Given:** the reference dataset on the reference host, the generator on another machine
* **When:** the reference load runs at pipeline depths 1 and 16 over 50 connections
* **Then:** throughput, p50/p99/p99.9 by command, resident memory and threads are reported with the raw `memtier_benchmark` output committed

## 6. Out of scope

* Replication, multiple replicas, environments (not decided — brief §7).

## 7. Quirks

* **The graceful stop is tested repeatedly because one green run proves little**: a signal that
  lands on the selector thread kills the process through `pselect`'s `EINTR` (research R-3).
* **Never profile kesh with an in-process, signal-based sampler** — same mechanism. Use `perf` from
  outside.
* **`SIGTERM` takes five seconds before the drain even starts** — kore's announce dwell
  (`ShutdownDeadlines.preDrainWait`), so a load balancer stops sending first. Measured on the release
  binary: `ANNOUNCE COMPLETED in 5.0 s`, `DRAIN` in 0.4 ms. B-16's grace period starts from it.
* **The HTTP port takes a descriptor from the connection ceiling's reserve** (research D-13):
  `maxclients` was 986 on the build machine without it and is 985 with it.
* **Every launcher inherits the 8080 default**; the conformance and bench scripts start kesh with
  `KESH_HTTP_PORT=off`, and two kesh processes on one host need two ports.

---

Why it is built this way, and what is still a hypothesis: [research-architecture](../research/research-architecture.md).
