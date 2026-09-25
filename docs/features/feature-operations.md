---
id: feature-operations
title: Observability and deployment
type: feature
status: active
owner: unassigned
involved_services: [server, deploy, bench, conformance]
client_entries: []
api: [endpoint-server, endpoint-http]
tags: [operations]
---

# Observability and deployment

## 1. Overview

Operators see what the instance is doing and run it like every other native service in the
portfolio: one image, one chart, probes, metrics, a memory limit, an ordered stop.

Built in B-15: `INFO`, the probes and the metrics; in B-16: the image, the chart and the graceful
stop; in B-17 and B-28: the reference load report (`bench/reports/b-17`, `b-28`). *Target*: the soak (B-18).

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
* **`SIGTERM` stops accepting connections, finishes the commands already read, writes their replies
  whole, saves if configured (`KESH_SAVE_ON_SHUTDOWN`), and exits** within the chart's grace period —
  which is derived from B-14's measured `SAVE` time, not chosen (research R-5, D-29). A command whose
  bytes arrived is either executed and answered, or not executed; never executed and left unanswered.
* The chart's memory limit is derived from `maxmemory` and B-11's measured peak ratio, with a
  placeholder for the day's drift until B-18 measures it ([deploy](../services/deploy.md)); the
  runtime cannot see the limit (research §1.2).

## 3. Flow

`SIGTERM` → kore's plan: announce (readiness false; then kore's 5 s wait) → the drain stage, kesh's
own participant (`KeshServer.stop`, B-16): stop accepting; interrupt every connection waiting for its
next command, let those that have read a batch execute it and write the replies — 5 s, then their
sockets are closed; then `SAVE` if `KESH_SAVE_ON_SHUTDOWN` is on — kore's `EngineDrain` is for Ktor
engines, and the RESP listener is not one → the release stages, empty → exit.

## 4. Code anchors

| Service | Code |
|---|---|
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/info/` — `INFO`, the per-command stats, the process's own numbers |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/http/` — the HTTP port and the exposition |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/KeshServer.kt` — the gates, the routes, the order of the start |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/Main.kt` — kore's plan: announce, then drain |
| conformance | `conformance/scripts/server/info.redis` — `INFO`'s shape against Redis 7.2 |
| bench | `bench/http/probe.sh` — the release binary's port: `promtool`, and readiness across `SIGTERM` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/net/RespConnection.kt` — a batch read is answered whole; the drain closes a connection after its replies |
| deploy | `deploy/Dockerfile`, `deploy/chart/` — the image and the chart, every sizing value derived ([deploy](../services/deploy.md)) |
| bench | `bench/drain/run.sh` — the graceful stop, repeated, in a kind cluster |

The load profiles are `bench/load/` (B-17); the soak is B-18's, reported beside them in `bench/reports/`.

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
* **Given:** 200 connected clients under load
* **When:** `SIGTERM`, repeated over many runs
* **Then:** no run crashes, and no client receives a truncated reply
* **And:** every command executed before the stop was answered, and the snapshot saved it
* **Automated:** `bench/drain/run.sh` in a kind cluster, 30 of 30 (`bench/reports/b-16/`); in the suite, `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/DrainTest.kt::every command the drain lets run is answered - the saved counter equals the replies received`

### Scenario: Metrics parse
* **When:** a test scrapes `GET /metrics`
* **Then:** the body parses as Prometheus text and carries `used_memory`, resident memory and thread count
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/http/HttpPortTest.kt::metrics parse as Prometheus text and carry memory threads and the command histogram` — with a strict reader of the format; Prometheus's own `promtool check metrics` in `bench/http/probe.sh`

### Scenario: INFO has Redis's shape
* **When:** `INFO`, whole or by section
* **Then:** every section and field kesh prints is in Redis 7.2's report, in its section and order
* **Automated:** `conformance/scripts/server/info.redis`

### Scenario: Reference load reported
* **Given:** the reference dataset on the reference host, the generator on another machine
* **When:** the reference load runs at pipeline depths 1 and 16 over 50 connections
* **Then:** throughput, p50/p99/p99.9 by command, resident memory and threads are reported with the raw `memtier_benchmark` output committed
* **Automated:** `bench/load/stand.sh` — at a sixteenth, on a two-host stand that is not the reference host, with `kesh-load` instead of `memtier_benchmark` (research D-30); report and raw output in `bench/reports/b-17/`. At an eighth kesh was OOM-killed: B-28

## 6. Out of scope

* Replication, multiple replicas, environments (not decided — brief §7).

## 7. Quirks

* **The graceful stop is tested repeatedly because one green run proves little**: until B-28 a signal
  that landed on the selector thread killed the process through `pselect`'s `EINTR` (research R-3);
  kesh's own loop retries it (D-31).
* **Never profile kesh with an in-process, signal-based sampler** — same mechanism. Use `perf` from
  outside.
* **`SIGTERM` takes five seconds before the drain even starts** — kore's announce dwell
  (`ShutdownDeadlines.preDrainWait`), so a load balancer stops sending first. Measured on the release
  binary: `ANNOUNCE COMPLETED in 5.0 s`, `DRAIN` in 0.4 ms. B-16's grace period starts from it.
* **The HTTP port takes a descriptor from the connection ceiling's reserve** (research D-13):
  `maxclients` was 986 on the build machine without it and 985 with it — until B-28 replaced the
  `FD_SETSIZE` ceiling with Redis's rule (research D-31).
* **Every launcher inherits the 8080 default**; the conformance and bench scripts start kesh with
  `KESH_HTTP_PORT=off`, and two kesh processes on one host need two ports.

---

Why it is built this way, and what is still a hypothesis: [research-architecture](../research/research-architecture.md).
