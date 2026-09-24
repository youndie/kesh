---
id: B-03
title: "bench: the reference dataset generator, deterministic by seed"
status: open
priority: P0
size: S
stage: stage-1-protocol
blocked_by: [B-01]
---

# B-03 — bench: the reference dataset generator, deterministic by seed

**Feature:** `feature-operations` — drafted in the *docs/layer-drafts* branch; the `epic` field is added when that document reaches `main`.

The scenarios, the heap probe (B-19), the conformance suite's large cases, the load report and the soak
all talk about the same dataset — the brief's §5a, carried as research appendix A. If each builds its own approximation, their
numbers describe different things.

- **The decision and its reason.** One generator, seeded, that emits the §5a shape as RESP command
  streams (for loading through the protocol) and as an in-process builder (for B-19, which has no
  protocol). The same seed gives the same bytes, so a result can be re-run months later.
- Rejected: generating with `memtier_benchmark` alone — it can load strings of a size, not hashes of
  8–20 fields or sorted sets of 1 000–100 000 members.
- Not covered: the load mix (80/20 reads/writes, Zipf) — that is B-17's `memtier_benchmark`
  profile, which reads the key names this generator writes.

- AC: The same seed produces byte-identical load scripts on two runs.
- AC: Key counts and sizes per part are within 2 % of the table in research appendix A, printed by the generator itself.
- AC: B-19 can build the same shape in-process from the same seed.

## Code anchors

| Module | Path |
|---|---|
| bench | `bench/src/` |
| bench | `bench/profiles/` |

Research: [research-architecture](../research/research-architecture.md).
