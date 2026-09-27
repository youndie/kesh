---
id: B-18
title: "24 h soak with TTL churn"
status: done
priority: P2
size: S
stage: stage-6-capacity
epic: feature-operations
blocked_by: [B-17]
---

# B-18 — 24 h soak with TTL churn

**Feature:** [feature-operations](../features/feature-operations.md).

A non-moving collector cannot compact (research R-2); over a day of TTL churn, resident memory may
drift away from `used_memory` even though nothing leaks.

- 24 h at the reference load, with resident memory, `used_memory`, thread count and latency
  percentiles per hour. The ratio of resident to `used_memory` per hour is the number B-16's memory
  limit needs.

- AC: No crash, and a per-hour table of resident memory, `used_memory`, their ratio and latency percentiles.
- AC: Research R-2 says whether the drift is bounded, with the table as evidence.

## Code anchors

| Module | Path |
|---|---|
| bench | `bench/soak/` |
| bench | `bench/reports/` |

## Decision (owner, 2026-09-25)

On the two-host stand, **with the subject host's other resident process (an idle database container)
left running** — the soak's question is memory drift, which a quiet neighbour barely touches; the
report says it was there.

## Iteration 1 (2026-09-25)

- **Running, detached on the stand, from 2026-09-25 13:09 UTC to about 2026-09-26 13:10 UTC.**
  kesh at `main` `1245f34` (md5 `26370382…`, the epoll transport and Pub/Sub) on the subject host,
  loaded at an eighth of §5a; `bench/soak/soak.sh` on the generator host drives `kesh-load` at pipeline
  1 in 24 runs of an hour, and after each reads `/metrics` into `soak/hours.tsv` (`~/kesh-soak` on
  the generator host). The subject host samples resident memory every minute into `~/kesh-soak/rss.txt`
  and kills kesh above 7 GB.
- **Nothing to act on until it ends**; iterations meanwhile take other items and do not resume this
  one. The next step: fetch `hours.tsv`, `rss.txt`, the hourly outputs; write the report and R-2.

Research: [research-architecture](../research/research-architecture.md).

### Iteration 2 — 2026-09-25: stopped by the owner

The soak was stopped at the owner's request at about 19:55 UTC, 6 h 46 min in, during its seventh hour:
**six whole hours** are in `hours.tsv`; the seventh's `kesh-load` was killed mid-hour, so `hour-7.*` is
partial and not a data point. kesh was stopped with `SIGTERM`; nothing of the soak runs on either host.
The owner asked that the hosts not be touched until they return to it, so nothing has been collected:
the data stays on the generator host (`~/kesh-soak/soak/`: `hours.tsv`, `hour-N.txt/.raw`,
`metrics-N.txt`) and the subject host (`~/kesh-soak/rss.txt`, sampled each minute). Next: collect those,
decide with the owner whether six hours answer research R-2 or a new 24 h run is needed — a new run
would take the binary with B-31's collector metrics, which the stopped one did not have.

### Iteration 3 — 2026-09-26: run 2 started

The owner asked for the soak again. **Run 1** (six whole hours, stopped) is kept as it was collected, in
`bench/reports/b-18/run-1-stopped/` — `hours.tsv`, the hourly outputs and `/metrics`, `rss.txt` — and moved
aside on both hosts; the subject address in its `kesh.log` is replaced by a placeholder. Its six hours
held resident memory at 1.91 → 1.87 × `used_memory`, with no error.

**Run 2 started 2026-09-26 17:46 UTC, due about 2026-09-27 17:47 UTC.** kesh at `main` `84d77ea` (md5
`36382908…`): the build with B-31's collector metrics, so each hour's `metrics-N.txt` now carries the
pauses too. The same generator as run 1, B-17 and B-28 (`kesh-load` md5 `b06b43b7…`), an eighth of §5a
loaded in 42.7 s with no error, pipeline 1, 24 runs of an hour. The subject side is now a script,
`bench/soak/subject.sh` (kesh detached, resident memory every minute into `rss.txt`, killed above 7 GB);
run 1's was typed by hand. The subject host's database container is still running, as decided.

### Iteration 4 — 2026-09-27: done

Run 2 ran its 24 hours, 2026-09-26 17:46 → 2026-09-27 17:50 UTC; kesh was then stopped with `SIGTERM`
and both hosts left idle. `bench/reports/b-18/`.

- **AC 1 — met.** No crash, 0 error replies in 24 runs; the per-hour table of resident memory,
  `used_memory`, their ratio and the latency percentiles is `run-2/soak/hours.tsv`, summarised in the
  report, with resident memory each minute in `run-2/rss.txt`.
- **AC 2 — met.** Research R-2: bounded — resident memory flat for the last fourteen hours, the ratio
  up 4 % as the data shrank. The chart's `dailyDriftPercent` is 5, measured, in place of the placeholder
  20; for 1 GiB of `maxmemory` the limit is 3.53 GiB (`helm template`), was 4.0.
- Beyond the item: the collector's pauses over the day, from B-31's metrics — no drift; the client's
  p99.9 is not the pause.
