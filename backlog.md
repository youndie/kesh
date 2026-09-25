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

## Open (13)

| Task | | Priority | Size | Blocked by |
|---|---|---|---|---|
| [B-21](docs/backlog/B-21-first-consumer-and-pubsub.md) `[?]` | Who is the first consumer, and does Pub/Sub belong in v1? | P0 | XS | - |
| [B-09](docs/backlog/B-09-sorted-sets.md) `[~]` | Sorted sets with logarithmic rank and range | P1 | L | B-05 |
| [B-10](docs/backlog/B-10-scan-family.md) `[ ]` | SCAN, HSCAN, SSCAN, ZSCAN with the completeness guarantee | P1 | M | B-06, B-07, B-08, B-09 |
| [B-11](docs/backlog/B-11-memory-accounting-and-noeviction.md) `[ ]` | Memory accounting, maxmemory and noeviction, checked against the container's limit | P1 | M | B-10 |
| [B-12](docs/backlog/B-12-eviction-policies.md) `[ ]` | Eviction policies with sampled LRU | P1 | M | B-11 |
| [B-13](docs/backlog/B-13-active-expiry.md) `[ ]` | Active expiry, Redis's algorithm as its source has it | P1 | S | B-05 |
| [B-14](docs/backlog/B-14-snapshots.md) `[ ]` | Snapshots: the format, SAVE, load at startup, torn-save safety, and their measured cost | P1 | L | B-10 |
| [B-15](docs/backlog/B-15-info-probes-metrics.md) `[ ]` | INFO, HTTP probes and Prometheus metrics | P1 | M | B-11 |
| [B-16](docs/backlog/B-16-image-chart-graceful-stop.md) `[ ]` | Image, chart, and a graceful stop that survives being repeated | P1 | M | B-14, B-15 |
| [B-17](docs/backlog/B-17-reference-load-report.md) `[ ]` | Reference load report on the reference host | P1 | M | B-03, B-12, B-14, B-22 |
| [B-22](docs/backlog/B-22-reference-host.md) `[?]` | Choose the reference host for the load report and the soak | P1 | XS | - |
| [B-23](docs/backlog/B-23-mutator-assists-stall-writes.md) `[ ]` | Writes stall for seconds while the keyspace grows: decide on the collector's mutator assists | P1 | S | - |
| [B-18](docs/backlog/B-18-soak-24h.md) `[ ]` | 24 h soak with TTL churn | P2 | S | B-17 |

## Closed (10)

**Protocol and core**

- [B-01](docs/backlog/B-01-project-skeleton.md) `[x]` - Project skeleton: the modules, sborka, kore, a linuxX64 binary that answers PING
- [B-02](docs/backlog/B-02-resp-parser-and-limits.md) `[x]` - resp: parser and writer, pipelining, inline commands, limits and the connection ceiling
- [B-03](docs/backlog/B-03-reference-dataset-generator.md) `[x]` - bench: the reference dataset generator, deterministic by seed
- [B-04](docs/backlog/B-04-differential-conformance-harness.md) `[x]` - conformance: the differential harness against Redis 7.2, raw bytes with declared normalisers
- [B-05](docs/backlog/B-05-strings-keyspace-lazy-expiry.md) `[x]` - Strings and keyspace on kesh's own hash table, lazy expiry
- [B-19](docs/backlog/B-19-managed-heap-probe.md) `[x]` - Heap probe: can plain Kotlin objects hold the reference dataset?
- [B-20](docs/backlog/B-20-define-cannot-serve.md) `[x]` - Define "the managed heap cannot serve the reference dataset" before B-19 reports

**The five types**

- [B-06](docs/backlog/B-06-hashes.md) `[x]` - Hashes, with a packed encoding for small ones
- [B-07](docs/backlog/B-07-lists.md) `[x]` - Lists
- [B-08](docs/backlog/B-08-sets.md) `[x]` - Sets

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

**The scope question is open, and it is not a formality.**
[B-21](docs/backlog/B-21-first-consumer-and-pubsub.md): the portfolio's one Redis consumer uses
only Pub/Sub, which v1 excludes. It blocks nothing formally; every item it could change is cheaper
to change before it starts.

**Feature links wait for the feature documents.** Items name their feature in the body. The `epic`
field is added by the pull request that brings that feature document to `main`, because a link to a
document that exists only in the drafts branch is a broken link here.

**Blocking is a fact, not a plan.** `blocked_by` lists what an item cannot be done without; an
order of preference belongs in this file, not in the field.
