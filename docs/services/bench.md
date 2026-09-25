---
id: bench
title: bench — reference dataset, heap probe, load profiles, soak
type: service
status: active
module: bench
tech_stack: [Kotlin, common source set, jvm and linuxX64, memtier_benchmark]
owner: unassigned
depends_on: [resp, memtier_benchmark]
publishes: [kesh-dataset executable]
---

# bench

## 1. Responsibility

Everything that measures, and the dataset every measurement talks about.

**Built (B-03):** the reference dataset generator — research appendix A as a deterministic stream of
entries (for an in-process caller) and as a RESP load script (for loading through the protocol),
with a summary that checks itself against the table.

**Built (B-17):** `kesh-load`, the reference load on the dataset's own keys (research D-30), and
`load/stand.sh`, which runs it on a two-host stand against kesh and Redis 7.2.

***Target*:** the heap probe (B-19),
the 24 h soak runner (B-18), and the reports with their raw output.

**Deliberately does not:** gate a build on a performance number (research D-9). The heap probe has a
verdict, but it is a design verdict on research D-3, not a performance gate.

## 2. API contracts

* **The dataset:** `ReferenceDataset(seed, scale).entries()` — a `Sequence<Entry>`, one part after
  another, streamed (the full dataset is never in memory; the generator's peak RSS is 36 MB). This is
  what B-19 builds its heap from.
* **The script:** `LoadScript` writes `SET … [EX]`, `HSET`, `RPUSH`, `SADD`, and `ZADD` in chunks of
  1 000 pairs, as RESP arrays of bulk strings — what `redis-cli --pipe` reads.
* **The command line:** `kesh-dataset [--seed N] [--scale F] [--out PATH|-] [--summary-only] [--check]`.
  The summary goes to stderr; `--check` exits 1 when a part is more than 2 % from the table.

## 2a. Code anchors

| File | What is there |
|---|---|
| `bench/src/commonMain/kotlin/io/github/youndie/kesh/bench/ReferenceDataset.kt` | the six parts, each with the arithmetic that sets its distribution |
| `bench/src/commonMain/kotlin/io/github/youndie/kesh/bench/Distributions.kt` | `SkewedRange`, and the stratified `PowerLaw` the leaderboards need |
| `bench/src/commonMain/kotlin/io/github/youndie/kesh/bench/Rng.kt` | SplitMix64 |
| `bench/src/commonMain/kotlin/io/github/youndie/kesh/bench/LoadScript.kt` | entries → RESP commands |
| `bench/src/commonMain/kotlin/io/github/youndie/kesh/bench/DatasetSummary.kt` | counts and user bytes against the table |
| `bench/src/nativeMain/kotlin/io/github/youndie/kesh/bench/Main.kt` | the `kesh-dataset` command line |
| `bench/src/commonMain/kotlin/io/github/youndie/kesh/bench/load/` | the load: the key catalogue, Zipf, §5a's mix, the latency histogram, the reply counter |
| `bench/src/nativeMain/kotlin/io/github/youndie/kesh/bench/load/Main.kt` | `kesh-load --load` and `--run` |
| `bench/load/stand.sh` | the two-host series: kesh, then Redis 7.2, per-thread CPU and memory beside the generator's output |
| `bench/src/commonTest/kotlin/io/github/youndie/kesh/bench/ReferenceDatasetTest.kt` | determinism, the pinned digest, shapes, fixed values |

## 3. How it is built

* **One seed, one dataset, for good.** The generator is SplitMix64 rather than `kotlin.random`, whose
  sequence is promised only within one Kotlin runtime version; each part forks its own stream, so
  changing one part never moves another. `ReferenceDatasetTest` pins the digest of seed 42 at scale
  0.0005 and runs on the JVM and on linuxX64 — equal on both. **That pin changes only on purpose**, in
  the same change as the generator, and reports then say which generator they used.
* **The table read backwards.** Each part draws inside its range with the mean its total implies;
  the leaderboards need a stratified power law (research appendix A, "How the generator reads the
  table"). Seed 42 at full scale: every part within 0.4 %.
* **One subject per host** (research §1.2, consequence 5), for everything that measures: the
  generator and the load run on another machine; no second resident process shares the subject's
  host.
* **Arms prove they differ**: for any comparison of build options, the binaries' md5 are compared
  before the run — an unknown `-Xbinary` option is a warning, and the binary comes out identical.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Module | [resp](resp.md) | the RESP writer the script is written with |
| External | `memtier_benchmark` (GPL-2.0, used as a tool only) | the reference load (brief D-8) — B-17 |
| External | `redis-cli --pipe` | loading a script into the oracle, or into kesh once it has data commands |
| Host | the reference host (not chosen — research Q-3, B-22) | B-17, B-18 |

## 6. Local setup

On the Linux build machine:

```bash
./gradlew :bench:linkReleaseExecutableLinuxX64
bench/build/bin/linuxX64/releaseExecutable/kesh-dataset.kexe --seed 42 --summary-only --check
bench/build/bin/linuxX64/releaseExecutable/kesh-dataset.kexe --seed 42 --scale 0.01 --out /tmp/ref-1pct.resp
redis-cli -p 6379 --pipe < /tmp/ref-1pct.resp
```

The full script is about 5 GB; the full summary takes 46 s.

## 8. Quirks

* **The 2 % holds at full scale only.** At scale 0.01 the 20 leaderboards are −11 % — twenty draws of
  a heavy tail. A scaled-down run states its scale.
* **TTLs are relative** (`EX` seconds), so a script is the same whenever it is generated — and its
  keys start expiring when it is loaded, not when it was written.
* **`memtier_benchmark` generates its own key names** (prefix plus number); reading this dataset's
  keys under the reference load is B-17's problem to solve, not something the generator provides.
* **The build machine is not a measurement host**: it builds, and it is shared. **The measurement
  hosts cannot build kesh** — they reach Maven Central but not GitHub or the portfolio's repository
  where kore lives. Build elsewhere, ship the binary (research §1.8).
* **The build machine's clocks disagree** (found in B-05, 2026-09-25). Its monotonic clock runs about
  9.8 % slow — 60.000 s by it were 65.87 s by its wall clock and 65.99 s by another machine's — and
  the wall clock catches up in steps of +2.93 s about every 32 s. So a tool timing requests by the
  wall clock, as `redis-benchmark` does, reports every request in flight across a step as ~2.93 s
  slower: Redis 7.2 on the same host, 100 M `SET`s in 56 s, has a maximum of 2934.783 ms and a
  next-slowest of 5.1 ms. And a duration the Kotlin/Native runtime logs, read off the monotonic clock,
  is about 10 % short. Check before trusting either — the steps show as jumps in
  `time.time() - time.monotonic()` sampled over a minute.
* **`redis-benchmark` records anything slower than 3 s as 3 s** (`redis/redis@7.2.5!/src/redis-benchmark.c`
  line 568): a maximum of 3000.3 ms is "3 s or more". A longer stall is read from the runtime's GC log.
