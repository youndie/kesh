# docs — kesh

A Redis-compatible (RESP2) in-memory store on Kotlin/Native. The documentation is layered; links run
top to bottom.

```
[ Research (why the architecture is what it is) ]
                     │
[ Feature (business + BDD) ]
                     │
                     ▼
[ API: RESP2 command groups and the HTTP port ]
                     │
                     ▼
[ Service: the modules — resp, store, snapshot, server, conformance, bench, deploy ]
```

| Layer | Directory | Answers | Source of truth |
|---|---|---|---|
| Research | `research/` | *why* it is built this way; what is verified, what is a hypothesis | the artefacts each fact names |
| Feature | `features/` | *what* the store does; BDD scenarios checked against the Redis oracle | this repository |
| API | `api/` | commands, replies, error strings per group; the HTTP port | Redis's documentation of each command, held by `conformance` |
| Service | `services/` | what each module owns, its configuration, deploy | this repository |

There is no `screens/` layer: kesh has no user interface.

**Where the layer documents are.** `main` describes what exists. A layer document reaches `main` in
the pull request that implements it, as `status: active`, with its scenarios' `**Automated:**` lines,
live code anchors, and whatever is not built yet marked *target*. The rest are drafted in the branch
*docs/layer-drafts* with `status: draft`. On `main`: `resp`, `server`,
`feature-resp-connection`, `endpoint-connection` (B-01, B-02), `bench` (B-03), `conformance` (B-04), `store`, `feature-strings`, `feature-keyspace`,
`endpoint-strings`, `endpoint-keyspace` (B-05), `feature-hashes`, `endpoint-hashes` (B-06),
`feature-lists`, `endpoint-lists` (B-07), `feature-sets`, `endpoint-sets` (B-08), `feature-sorted-sets`,
`endpoint-sorted-sets` (B-09).

**Backlog** — [backlog.md](../backlog.md): the index and the decisions; the items themselves are
one file each in [`backlog/`](backlog/), cited as `[B-12](backlog/B-12-some-slug.md)`.

## Conventions

- **`id`** in the frontmatter is unique and equals the filename.
- Cross-layer links are ids in the frontmatter and ordinary markdown links in the body.
- One document, one entity.
- Scenarios are written from behaviour checked against the oracle (Redis 7.2, research D-16): real
  replies and real error strings, byte for byte. Until a scenario is implemented it is marked
  *target*.
- **The primary consumer is a coding agent.** Every document carries code anchors — paths, not
  copies. Do not duplicate what lives in code (config keys, command tables); give the path.
- Language: English. Command names, error strings and config keys verbatim as Redis spells them.

## Templates

`templates/` holds a copy of the document templates, so the format travels with the repository.
There is no screen template: the product has no screens.

## Checks

```bash
pip install pyyaml
make check      # the gate CI runs: backlog index, docs check, coverage map, then the reports
make fix        # regenerate the backlog index and append missing coverage-map lines
```

## Coverage map

The list below is **checked** against the files on disk: a document missing here, or an entry with
no file behind it, fails `coverage_map.py`.

### Research (1)

- [x] [research-architecture](research/research-architecture.md) — verified facts, the deviations from the brief, decisions D-1…D-23, risks, the reference dataset

### Services (5)

- [x] [resp](services/resp.md) — the RESP2 parser and writer
- [x] [store](services/store.md) — the keyspace, strings and the four collection kinds, keys, lazy expiry
- [x] [server](services/server.md) — the binary: listener, connections, the store thread, ordered stop
- [x] [bench](services/bench.md) — the reference dataset generator; heap probe, load profiles and soak *target*
- [x] [conformance](services/conformance.md) — the differential harness against Redis 7.2, and the Lettuce smoke

### Features (7)

- [x] [feature-strings](features/feature-strings.md) — strings and counters
- [x] [feature-hashes](features/feature-hashes.md) — hashes, packed where Redis packs them
- [x] [feature-lists](features/feature-lists.md) — lists of packed chunks
- [x] [feature-sets](features/feature-sets.md) — sets, packed where small
- [x] [feature-sorted-sets](features/feature-sorted-sets.md) — sorted sets on a skiplist with spans
- [x] [feature-keyspace](features/feature-keyspace.md) — keys, `SCAN` and lazy expiry; active expiry *target*
- [x] [feature-resp-connection](features/feature-resp-connection.md) — RESP2 and inline, pipelining, auth, Redis's request limits, the connection ceiling

### API (7)

- [x] [endpoint-strings](api/endpoint-strings.md) — the twenty string commands
- [x] [endpoint-hashes](api/endpoint-hashes.md) — the eleven hash commands and `HSCAN`
- [x] [endpoint-lists](api/endpoint-lists.md) — the ten list commands
- [x] [endpoint-sets](api/endpoint-sets.md) — the eleven set commands and `SSCAN`
- [x] [endpoint-sorted-sets](api/endpoint-sorted-sets.md) — the fifteen sorted set commands and `ZSCAN`
- [x] [endpoint-keyspace](api/endpoint-keyspace.md) — key commands, expiry and `SCAN`
- [x] [endpoint-connection](api/endpoint-connection.md) — `PING`, `ECHO`, `QUIT`, `AUTH`, `HELLO`, `SELECT`, `CLIENT`, `COMMAND`; protocol errors
