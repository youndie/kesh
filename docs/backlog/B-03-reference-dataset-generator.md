---
id: B-03
title: "bench: the reference dataset generator, deterministic by seed"
status: done
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

## Findings

### Iteration 1 — 2026-09-24, done

Acceptance, on the Linux build machine with the release `kesh-dataset`:

- **Same seed, same bytes:** seed 42 at scale 0.01 written twice — equal SHA-256
  (`bb93e9ad6c35…`, 54.7 MB); seed 43 differs. `ReferenceDatasetTest` pins the digest of seed 42 at
  scale 0.0005 and passes on the JVM and on linuxX64, so the dataset is the same on both.
- **Within 2 % of appendix A, printed by the generator:** seed 42 at full scale, `--check` passes —
  15 802 000 keys, 4 259 648 349 bytes of user data against 4 260 000 000, every part within 0.4 %.
  Seeds 7 and 1 000 the same. Before the two fixes below, `--check` failed (profiles −2.5 %,
  leaderboards +5.0 %), which is its positive control. 46 s, 36 MB peak RSS.
- **In-process for B-19:** `ReferenceDataset(seed, scale).entries()` is the same stream the script is
  written from.
- **Real path:** the 1 % script loaded into Redis 7.2 with `redis-cli --pipe` — 0 errors, `DBSIZE`
  158 020, `user:1001` is Ada on `pro`, `board:2026-09` present, counters expiring.
- 22 tests (11 per target), `ktlintCheck` clean. Mutations caught: the fixed user dropped; the
  stratification lost; `ZADD` chunks of 2 000; a 50 % TTL share; the generator's constant changed.

Found on the way, decided here and recorded in research appendix A:

- **"User data" is keys plus values** (plus 8 bytes per score); values alone cannot match the
  counters' 0.05 GB.
- **The leaderboard row is inconsistent as written**: uniform 1 000–100 000 members hold 1.6 GB, not
  0.3. The generator draws a stratified power law with a mean near 9 700 — research §0, row 12.
- **Not `kotlin.random`**: its sequence is promised only within one runtime version; SplitMix64 is.
- **A baseline from Redis**: at 1 %, Redis 7.2 holds 42.1 MB of user data in 73.1 MB of
  `used_memory` (1.74×), the figure B-11's ratio will be read beside.

## Code anchors

| Module | Path |
|---|---|
| bench | `bench/src/commonMain/kotlin/io/github/youndie/kesh/bench/ReferenceDataset.kt` |
| bench | `bench/src/commonMain/kotlin/io/github/youndie/kesh/bench/LoadScript.kt` |
| bench | `bench/src/nativeMain/kotlin/io/github/youndie/kesh/bench/Main.kt` |

Research: [research-architecture](../research/research-architecture.md).
