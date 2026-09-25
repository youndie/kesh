# B-19: what the collector's pause is made of, on the reference dataset's shape

**Verdict (research D-3, as amended by the owner on 2026-09-24): D-3 stands.** kesh keeps its data in
plain Kotlin objects, in the packed encoding, built with 256 KiB allocator pages. The collector's
stop-the-world pause is a known limitation, and this report is its explanation: **the pause is the
allocator's page bookkeeping at the end of marking, and it follows the number of allocator pages —
not the number of objects, and not the garbage made during marking.** The full-scale figure is taken
on the reference host at the last stage (B-17, B-22) and reported, not gated (D-9).

All runs: the build machine (20 cores, 16 GB, WSL2), shared with another session's work — each run
started only once at least 6–9 GB were free and the 1-minute load average was under 1.5
(`bench/heap-probe/series.sh`). Kotlin/Native 2.4.20, CMS (the default), seed 42, 60 s of churn at
the maximum rate one thread reaches unless a rate is given. Every stop-the-world pause is one sample
(`bench/heap-probe/pauses.py`). Raw stdout and GC logs of every run: `raw.tgz` beside this file;
`bench/heap-probe/epochs.py` reads them epoch by epoch.

**A caveat found later (B-05, 2026-09-25):** the build machine's monotonic clock, which the runtime's
GC log reads, was then running about 9.8 % slow (the bench service's quirks). Whether it did during
these runs was not recorded. If it did, every pause below is about 10 % short; the comparison between
the arms, taken interleaved on the same clock, is not affected.

## 1. The decisive run: the same heap, pages of 16 and 256 KiB

Interleaved in one series, so both arms saw the same host. md5 `8e86069e…` (16 KiB, what sborka's
`native-service` gives every native service) against `a6588769…` (256 KiB, the compiler's default).

| arm | run | pauses | p50 ms | p99 ms | first two after load, ms | steady median ms | steady max ms | RSS peak |
|---|---|---|---|---|---|---|---|---|
| 16 KiB | r1 | 26 | 9.99 | 119.70 | 119.7, 102.0 | 13.39 | 21.20 | 4 237 MB |
| 256 KiB | r1 | 28 | 0.76 | 18.38 | 16.3, 18.4 | 1.43 | 2.16 | 4 290 MB |
| 16 KiB | r2 | 26 | 8.07 | 139.02 | 139.0, 111.1 | 15.51 | 19.23 | 4 244 MB |
| 256 KiB | r2 | 30 | 0.78 | 8.08 | 8.1, 7.0 | 1.25 | 2.02 | 4 294 MB |
| 16 KiB | r3 | 24 | 7.98 | 83.56 | 83.6, 61.3 | 17.42 | 28.46 | 4 237 MB |
| 256 KiB | r3 | 28 | 0.83 | 13.89 | 9.6, 13.9 | 1.46 | 1.82 | 4 304 MB |

Packed encoding, a quarter of the dataset (3.95 M keys), 14.5 M objects marked and 32.5 M swept per
epoch in both arms — the heap and the churn are the same; only the page size moved. **The pause fell
about tenfold; resident memory rose 1 %.**

## 2. Why: the code in the pause

`kotlin-native/runtime/src/gc/common/cpp/MainGCThread.hpp` (v2.4.20), lines 56–69: after
`markInSTW()` returns with the world stopped ("GC stop the world: prepare to sweep",
`gc/cms/cpp/ConcurrentMark.cpp:113`), every thread's allocator and the heap run `prepareForGC()`
before the world resumes. In the custom allocator that is `Heap::PrepareForGC`
(`alloc/custom/cpp/Heap.cpp:24`), which for every size class calls `PageStore::PrepareForGC`
(`alloc/custom/cpp/PageStore.hpp:24`):

- `unswept_.TransferAllFrom(std::move(used_))` — which walks the whole `used_` list to find its tail
  (`AtomicStack.hpp:79–81`): one step per page, each on a different page;
- `while ((page = empty_.Pop())) page->Destroy();` — every page the previous sweep emptied is freed,
  one at a time.

Both are linear in the number of pages, inside the stop-the-world pause. A 16 KiB page holds a
sixteenth of a 256 KiB one, so the same heap has sixteen times as many.

## 3. What the explanation has to account for — and does

| observation | runs | page count explains it? |
|---|---|---|
| The first CMS pause is ~10 µs; all of it is the second | every run | yes: the root-set pause does no page work |
| Naive marks 4.7× the objects of packed at 1/8 (34.4 M against 7.3 M) and pauses the same (p99 30.6–39.5 ms against 35.2–37.5 ms) | `default-*-0.125` in `raw.tgz` | yes: small objects share pages; the page counts are close. **This refuted "the pause follows the objects marked"** |
| Holding the churn at 100 k, 30 k and 10 k commands/s moved the garbage per epoch 10× (31.6 M → 3.2 M objects) and the pause not at all | `probe-rate/` | yes: the heap's pages stay. **This refuted "the pause follows the garbage made during marking"** |
| The pause grows with scale: 16 KiB packed p99 35–37 ms at 1/8, 60–71 at 1/4, 228–256 at 1/2 | `probe-series/` | yes: pages grow with the heap |
| The first two collections after the load pause longest (61–139 ms at 1/4 with 16 KiB, 7–18 ms with 256 KiB) | every run | consistent: the load leaves the most empty pages behind for the first `PrepareForGC` to free |
| `pmcs` pauses 600–890 ms | `probe-series/pmcs-*` | not this mechanism: pmcs marks in the pause |

## 4. What stays unknown

- **The full-scale figure.** With 256 KiB pages the quarter-scale pause was 1.3–1.5 ms steady and
  7–18 ms right after the load. The full dataset has about four times the pages; by the mechanism
  that is ~5–6 ms steady and ~30–70 ms after a load — an extrapolation, measured on the reference
  host at the last stage.
- **The post-load transient** is explained only as far as "more empty pages then"; the page counts
  themselves are not logged by the runtime and were not counted.
- **`gcMarkSingleThreaded=true`** changed nothing in the binary (md5 equal to the default's) — the
  option is not honoured on 2.4.20, so that arm's three rows are three more default runs, and no
  statement about single-threaded marking is made.
- **The probe's packed churn is harsher than kesh's will be**: it unpacks a hash or a list into
  objects and packs it again (~30 objects per `HSET`, ~200 per `LPUSH`), where the store will edit
  bytes in place. By the rate runs this changes the garbage, which the pause does not follow.

## 5. What it changes

- **kesh is built with 256 KiB pages** (research D-19): `server/build.gradle.kts`,
  `allocatorPageSize = 256`, overriding sborka's 16.
- **The packed encoding stays, for memory rather than for pauses.** Naive held 2.1 GB live at 1/8
  where packed held 0.97 GB; at full scale naive would need roughly 35 GB of resident memory.
- **Research §1.2's "the lever is the object count" was wrong** and is corrected there.
- The collector schedules a heap twice the live set (`alive … target …` in the scheduler log);
  peak RSS ran at 2.2× `alive`. Packed at full scale: ~8 GB live, ~17 GB resident — the reference
  host needs more than 16 GB (B-22).

The earlier preliminary rows, including three runs made on the 4-core host without asking, are in
`../b-19-preliminary/`.
