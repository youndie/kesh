# kesh

An in-memory data store that speaks the Redis protocol (RESP2), built as a single native
`linuxX64` binary on Kotlin/Native — so that `redis-cli`, `memtier_benchmark` and ordinary Redis
client libraries work against it unchanged.

> **Status: v1 in progress; nothing is published.** Built: the connection commands, strings, keys and
> the four collection kinds, `maxmemory` with eviction, active expiry, snapshots, `INFO`, probes and
> metrics, an image and a chart, and Pub/Sub — held byte for byte to Redis 7.2. Measured at a sixteenth
> of the reference dataset, not yet at full scale. Start at [docs/](docs/README.md).

## What v1 is meant to be

RESP2 over TCP with pipelining and inline commands; strings, hashes, lists, sets and sorted sets with
their core commands; per-key expiry, lazy and active; `maxmemory` with eviction policies; snapshot
persistence in kesh's own format; `INFO`, HTTP health and Prometheus metrics, a container image and a
Helm chart. One instance per deployment.

## What it will deliberately not do

Cluster mode, replication, Sentinel; RESP3; transactions, Lua, modules, blocking commands, ACL users
beyond one password; Redis's RDB and AOF file formats. Pub/Sub was out of the brief's scope and is
now in it — see [feature-pubsub](docs/features/feature-pubsub.md).

## Documentation

- [docs/research/research-architecture.md](docs/research/research-architecture.md) — what was
  verified and where, what the research changed in the brief, what is still a hypothesis
- [backlog.md](backlog.md) — stages, items, the decisions behind the order
- [CLAUDE.md](CLAUDE.md) — how to work in this repository

Redis is a trademark of Redis Ltd. This project is not affiliated with, endorsed by, or sponsored by
Redis Ltd.
