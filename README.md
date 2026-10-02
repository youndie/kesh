# kesh

An in-memory data store that speaks the Redis protocol (RESP2), built as a single native
`linuxX64` binary on Kotlin/Native — so that `redis-cli` and ordinary Redis client libraries work
against it unchanged.

> **Status: v1 built, every backlog item closed; no release is published.** Every command kesh has
> is held byte for byte to Redis 7.2 by a differential harness. Measured up to an eighth of the
> reference dataset, on machines that are not reference hosts — full scale is arithmetic, not a
> measurement. Start at [docs/](docs/README.md).

## What it does

- **RESP2 over TCP**, pipelining and inline commands, one shared password, Redis's request limits.
- **Strings, hashes, lists, sets and sorted sets** with their core commands; keys, `SCAN` and its
  family; per-key expiry, lazy and active.
- **`maxmemory`** with Redis's eviction policies, LFU excepted; a `maxmemory` the container's memory
  limit cannot hold is refused.
- **Snapshots** in kesh's own format: `SAVE`, `BGSAVE` through fork, loaded before the listener binds.
- **Pub/Sub**: `PUBLISH`, `SUBSCRIBE`, `PSUBSCRIBE` in RESP2 subscribe mode.
- **Operations**: `INFO`, HTTP health probes and Prometheus metrics — the collector's pauses
  included — a container image, a Helm chart sized from measurements, an ordered stop that answers
  what it read.

One thread reads, executes and writes, on kesh's own `epoll` loop — research D-14 and D-31 say why.

## What it will deliberately not do

Cluster mode, replication, Sentinel; RESP3; transactions, Lua, modules, blocking commands, ACL users
beyond one password; Redis's RDB and AOF file formats; `SHUTDOWN`, `TIME`, `MEMORY USAGE`, `DEBUG`.

## How fast

At a sixteenth of the reference dataset on a two-host stand, 50 connections
([bench/reports/b-28](bench/reports/b-28/README.md)): at pipeline 1, 14 249–14 683 operations/s
against Redis 7.2's 14 640–15 126 on the same host, p99 8–9 ms against 7; at pipeline 16, 77 467–79 637
against 134 932–135 763, limited by the one thread that runs every command.

For how long: 24 hours of the same mix at pipeline 1 and an eighth of the dataset, with TTL churn,
ran without an error and held resident memory flat — 2.5 GiB for the last fourteen hours,
1.84–1.91 × `used_memory`; the collector's stop-the-world pause averaged about 1 ms
([bench/reports/b-18](bench/reports/b-18/README.md)).

## Build and run

On Linux (the target is `linuxX64` only):

```bash
./gradlew :server:linkReleaseExecutableLinuxX64
KESH_PORT=6379 server/build/bin/linuxX64/releaseExecutable/kesh.kexe
```

Configuration is environment variables, `KESH_*` — the list is in
[services/server.md](docs/services/server.md). The image and the chart are in [deploy/](deploy/),
described in [services/deploy.md](docs/services/deploy.md). Two build properties exist for
measurements, each a different binary: `-Pkesh.runtimeLogs=true` (the collector's log) and
`-Pkesh.allocatorPageSize=16` (the comparison arm of research D-19).

## Checking a change

- The test suites run on Linux, on the JVM and `linuxX64`; the exact set is in [CLAUDE.md](CLAUDE.md).
- `conformance/run.sh` (needs Docker) runs every script against kesh and Redis 7.2 and must end with
  "all agree" — the rule for any change to a reply.
- `make check` is the documentation gate, the one CI runs.

## Documentation

- [docs/research/research-architecture.md](docs/research/research-architecture.md) — what was
  verified and where, what the research changed in the brief, what is still a hypothesis
- [backlog.md](backlog.md) — stages, items, the decisions behind the order
- [CLAUDE.md](CLAUDE.md) — how to work in this repository

Redis is a trademark of Redis Ltd. This project is not affiliated with, endorsed by, or sponsored by
Redis Ltd.
