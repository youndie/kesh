# Backlog: kesh

> Role of this document: the product backlog. **One file per item in
> [`docs/backlog/`](docs/backlog/)** — `B-NN-<slug>.md`. What lives here is the index (generated)
> and everything that is not an item: the goal, the stages, and the decisions.
>
> New item: copy [`docs/templates/backlog-item.md`](docs/templates/backlog-item.md), take the next
> free `B-NN`, and run `make fix` after editing.

## Goal

A native `linuxX64` store that `redis-cli`, `memtier_benchmark` and ordinary Redis clients use
unchanged, holding the reference dataset
([research appendix A](docs/research/research-architecture.md#appendix-a-the-reference-dataset-the-briefs-5a))
in one process. Correctness first — every command held to Redis by a differential oracle — then
capacity, then operations.

Items B-01 … B-18 are the brief's §9, one to one and in the same order. B-19 … B-22 were added by
research, and the reason for each is in
[research §0](docs/research/research-architecture.md#0-what-research-changed-in-the-brief).

## Stages

A stage is a field on the item, not a directory.

| Stage id | Stage | What it is |
|---|---|---|
| `stage-1-protocol` | Protocol and core | a native binary that `redis-cli` talks to; the heap probe has a verdict; strings and keys work; the conformance harness runs |
| `stage-2-types` | The five types | hashes, lists, sets and sorted sets pass the conformance suite |
| `stage-3-memory` | Memory limit and expiry | `maxmemory`, eviction and active expiry, measured on the reference dataset |
| `stage-4-persistence` | Snapshots | `SAVE`, load at startup, crash safety, their cost measured |
| `stage-5-operations` | Operations | `INFO`, metrics, image, chart, graceful stop |
| `stage-6-capacity` | Capacity and soak | the reference dataset served under the reference load, reported per D-9, plus a 24 h soak |

## Marks

`[ ]` open · `[~]` in progress · `[x]` done · `[?]` open question · `[-]` dropped

<!-- BEGIN INDEX -->

## Open (3)

| Task | | Priority | Size | Blocked by |
|---|---|---|---|---|
| [B-18](docs/backlog/B-18-soak-24h.md) `[ ]` | 24 h soak with TTL churn | P2 | S | B-17 |
| [B-25](docs/backlog/B-25-bgsave-through-fork.md) `[~]` | BGSAVE through fork, with the child's collector assists off | P2 | M | B-14 |
| [B-26](docs/backlog/B-26-maxmemory-container-check.md) `[ ]` | Refuse a maxmemory above the container's memory budget | P2 | S | B-11 |

## Closed (25)

**Protocol and core**

- [B-01](docs/backlog/B-01-project-skeleton.md) `[x]` - Project skeleton: the modules, sborka, kore, a linuxX64 binary that answers PING
- [B-02](docs/backlog/B-02-resp-parser-and-limits.md) `[x]` - resp: parser and writer, pipelining, inline commands, limits and the connection ceiling
- [B-03](docs/backlog/B-03-reference-dataset-generator.md) `[x]` - bench: the reference dataset generator, deterministic by seed
- [B-04](docs/backlog/B-04-differential-conformance-harness.md) `[x]` - conformance: the differential harness against Redis 7.2, raw bytes with declared normalisers
- [B-05](docs/backlog/B-05-strings-keyspace-lazy-expiry.md) `[x]` - Strings and keyspace on kesh's own hash table, lazy expiry
- [B-19](docs/backlog/B-19-managed-heap-probe.md) `[x]` - Heap probe: can plain Kotlin objects hold the reference dataset?
- [B-20](docs/backlog/B-20-define-cannot-serve.md) `[x]` - Define "the managed heap cannot serve the reference dataset" before B-19 reports
- [B-21](docs/backlog/B-21-first-consumer-and-pubsub.md) `[x]` - Who is the first consumer, and does Pub/Sub belong in v1?
- [B-27](docs/backlog/B-27-pubsub.md) `[x]` - Pub/Sub: PUBLISH, SUBSCRIBE and PSUBSCRIBE in RESP2 subscribe mode

**The five types**

- [B-06](docs/backlog/B-06-hashes.md) `[x]` - Hashes, with a packed encoding for small ones
- [B-07](docs/backlog/B-07-lists.md) `[x]` - Lists
- [B-08](docs/backlog/B-08-sets.md) `[x]` - Sets
- [B-09](docs/backlog/B-09-sorted-sets.md) `[x]` - Sorted sets with logarithmic rank and range
- [B-10](docs/backlog/B-10-scan-family.md) `[x]` - SCAN, HSCAN, SSCAN, ZSCAN with the completeness guarantee

**Memory limit and expiry**

- [B-11](docs/backlog/B-11-memory-accounting-and-noeviction.md) `[x]` - Memory accounting, maxmemory and noeviction
- [B-12](docs/backlog/B-12-eviction-policies.md) `[x]` - Eviction policies with sampled LRU
- [B-13](docs/backlog/B-13-active-expiry.md) `[x]` - Active expiry, Redis's algorithm as its source has it
- [B-23](docs/backlog/B-23-mutator-assists-stall-writes.md) `[x]` - Writes stall for seconds while the keyspace grows: decide on the collector's mutator assists

**Snapshots**

- [B-14](docs/backlog/B-14-snapshots.md) `[x]` - Snapshots: the format, SAVE, load at startup, torn-save safety, and their measured cost

**Operations**

- [B-15](docs/backlog/B-15-info-probes-metrics.md) `[x]` - INFO, HTTP probes and Prometheus metrics
- [B-16](docs/backlog/B-16-image-chart-graceful-stop.md) `[x]` - Image, chart, and a graceful stop that survives being repeated
- [B-24](docs/backlog/B-24-taken-port-aborts.md) `[x]` - A taken port aborts the server with a core dump instead of exiting cleanly

**Capacity and soak**

- [B-17](docs/backlog/B-17-reference-load-report.md) `[x]` - Reference load report on the reference host
- [B-22](docs/backlog/B-22-reference-host.md) `[x]` - Choose the reference host for the load report and the soak
- [B-28](docs/backlog/B-28-heap-runaway-under-load.md) `[x]` - The heap runs away under the reference load: an epoch that sweeps nothing doubles the target

<!-- END INDEX -->

## Decisions worth not re-litigating

**The heap is measured before the store is built on it.**
[B-05](docs/backlog/B-05-strings-keyspace-lazy-expiry.md) is blocked by
[B-19](docs/backlog/B-19-managed-heap-probe.md), which the brief did not have. The brief measures
capacity in stage 6, after every structure that depends on D-3 exists; the only Kotlin/Native heap
measured in the portfolio at a size near the reference dataset was already pausing 20–35 ms at a
quarter of it. B-19 is a few days; finding out in stage 6 is the whole project.

**The threshold comes before the number.**
[B-20](docs/backlog/B-20-define-cannot-serve.md) is a question for the owner and has to be answered
before B-19's pull request opens. A threshold written after the table is written to fit the table.

**The scope question is answered: Pub/Sub is in.**
[B-21](docs/backlog/B-21-first-consumer-and-pubsub.md): the portfolio's one Redis consumer uses
only Pub/Sub, which the brief excluded. The owner added it on 2026-09-25 (research D-26), and
[B-27](docs/backlog/B-27-pubsub.md) builds it; kompot's bus is the first consumer.

**Feature links wait for the feature documents.** Items name their feature in the body. The `epic`
field is added by the pull request that brings that feature document to `main`, because a link to a
document that exists only in the drafts branch is a broken link here.

**Blocking is a fact, not a plan.** `blocked_by` lists what an item cannot be done without; an
order of preference belongs in this file, not in the field.
