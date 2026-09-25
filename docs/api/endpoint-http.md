---
id: endpoint-http
title: HTTP port
type: api_endpoints
status: active
services:
  - server
contract_source:
  - "kesh:server KeshServer.route"
parent_feature: feature-operations
---

# API: HTTP port

Probes and metrics on a separate port for the cluster's internal network. Part of [feature-operations](../features/feature-operations.md).

> **Built (B-15).** `KESH_HTTP_PORT`, 8080 by default, `off` for none; bound on `KESH_BIND`, **before**
> the snapshot loads, so the probes answer while it does — on the same `epoll` loop as RESP since
> B-28 (research D-31). One request per connection
> (`Connection: close`), `GET` only; the request line and headers must arrive within 5 s and 8 KiB.
> kore's gates decide the probes' answers (`kore-core`, `io.github.youndie.kore.health`).

## Routes — all of them

| Route | Auth tier | 200 | 503 |
|---|---|---|---|
| `GET /health/live` | none | `alive` | `wedged: <reason>` — kore `LivenessGate`; nothing in kesh declares itself wedged yet |
| `GET /health/started` | none | `started` — a latch | `starting: listener, snapshot` — what is still outstanding (kore `StartupGate`) |
| `GET /health/ready` | none | `ready` | `not ready: starting: …` until the start completes; `not ready: shutting down` from kore's announce stage on (kore `ReadinessGate`) |
| `GET /metrics` | none | Prometheus text 0.0.4 | — |

`/metrics`, all of it:

| Metric | Type | What |
|---|---|---|
| `kesh_resident_memory_bytes` | gauge | `VmRSS` of the process — answered during the load too |
| `kesh_threads` | gauge | threads of the process — answered during the load too |
| `kesh_build_info{allocator_page_size_kb}` | gauge | always 1; the allocator page size the binary was built with, `-Pkesh.allocatorPageSize` (B-30, research D-19) — answered during the load too |
| `kesh_used_memory_bytes`, `kesh_maxmemory_bytes` | gauge | `INFO`'s `used_memory` and `maxmemory` |
| `kesh_keys`, `kesh_keys_with_expiry` | gauge | the keyspace and its expiry index |
| `kesh_expired_keys_total`, `kesh_evicted_keys_total` | counter | `INFO stats`' `expired_keys`, `evicted_keys` |
| `kesh_connected_clients` | gauge | RESP connections open |
| `kesh_connections_received_total`, `kesh_rejected_connections_total` | counter | accepted, and refused at `maxclients` |
| `kesh_command_duration_seconds{command}` | histogram | time on the store thread per command (`get`, `config\|get`), 50 µs to 1 s; `_count` is commands run |

The store's numbers are copied on the store thread (research D-14) and appear once the start has
completed; before that, only the process's two.

## Handlers (code anchors)

| Group | Handler |
|---|---|
| HTTP port | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/net/HttpConnection.kt` |
| Routes | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/KeshServer.kt` — `route` |
| Metrics | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/http/Metrics.kt` |

## Errors

| Condition | Reply |
|---|---|
| a path not listed | `404` |
| a method other than `GET` | `405` |
| a request line that is not `<method> <path> HTTP/1.x` | `400` |
| no blank line within 5 s or 8 KiB | the connection is closed without a reply |
