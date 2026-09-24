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

**Where the layer documents are.** `main` describes what exists, and nothing is implemented yet, so
`features/`, `api/` and `services/` are drafted in the branch *docs/layer-drafts* with
`status: draft`. Each reaches `main` in the pull request that implements it, as `status: active`,
with its scenarios' `**Automated:**` lines and live code anchors. Until then this tree holds the
research and the backlog.

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

- [x] [research-architecture](research/research-architecture.md) — verified facts, the deviations from the brief, decisions D-1…D-17, risks, the reference dataset
