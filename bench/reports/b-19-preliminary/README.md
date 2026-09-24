# B-19, preliminary: the managed heap at 1 % and 25 % of the reference dataset

**Not a verdict.** Research D-3 reads the threshold (collector pause p99 ≤ 10 ms) at full scale, with
one subject on the host. Nothing here is either: the build machine was shared with another session's
builds and containers, and full scale does not fit in its memory. The owner deferred the full-scale
measurement to the last stage, on the reference host (B-22). These rows say what to expect.

Binary: `kesh-heap-probe` (md5 `6ec0c6e540de6cee4c9c6f96184e0af2`), Kotlin/Native 2.4.20, default
collector (CMS), `fixedBlockPageSize=16`, `-Xruntime-logs=gc=info,gcScheduler=info`. Seed 42, 60 s of
churn after the load (5 s for the 1 % smoke runs). Read with `bench/heap-probe/pauses.py`: every
stop-the-world pause is one sample.

| run | host | keys | RSS loaded | RSS peak | alive | marked | pauses | p50 ms | p99 ms | max ms | ops/s |
|---|---|---|---|---|---|---|---|---|---|---|---|
| naive 0.01 | build machine, shared | 158 020 | 192 MB | 348 MB | 168 MB | 2.68 M | 18 | 0.11 | 2.04 | 2.04 | 2.36 M |
| packed 0.01 | build machine, shared | 158 020 | 154 MB | 183 MB | 81 MB | 0.59 M | 106 | 0.36 | 3.66 | 3.98 | 458 k |
| packed 0.25 | build machine, shared | 3 950 500 | 3 177 MB | 4 237 MB | 1 913 MB | 14.4 M | 38 | 5.36 | **70.84** | 70.84 | 311 k |
| packed 0.25 r1 | bench-a (4 cores) | 3 950 500 | 3 289 MB | 4 297 MB | 1 879 MB | 14.3 M | 14 | 14.42 | **199.63** | 199.63 | 112 k |
| packed 0.25 r2 | bench-a | 3 950 500 | 3 231 MB | 4 260 MB | 1 880 MB | 14.3 M | 14 | 13.95 | **181.85** | 181.85 | 113 k |
| packed 0.25 r3 | bench-a | 3 950 500 | 3 222 MB | 4 255 MB | 1 880 MB | 14.3 M | 14 | 12.70 | **175.58** | 175.58 | 116 k |

The raw stdout and GC log of the build-machine runs are beside this file. The bench-a runs were made
without asking while that host was carrying other measurements — they may have disturbed those, and
been disturbed by them — and their raw logs were deleted with the rest of the probe's files there;
only the rows above remain. That host is not used again until the last stage.

## What the rows say, as far as they can

- **The first CMS pause is ~10 µs; the second — the end of marking — is the whole story.** At 25 %
  on the build machine the second pauses were 5.4–13.7 ms in 17 of 19 epochs, and 49 and 71 ms in
  the other two. Median around 10 ms, at a quarter of the dataset.
- **The packed encoding already fails the line at a quarter of the dataset**, on both hosts. The
  full dataset has four times the objects to mark; the portfolio's earlier study saw this pause grow
  with the heap (research §1.2).
- **The slower host paused longer, not shorter**: 4 cores (bench-a) against 20 (build machine),
  a quieter host, and p99 176–200 ms against 71 ms. The pause here follows CPU speed and mark work,
  not neighbours — which also means the reference host's CPU belongs in the verdict's conditions.
- **Memory**: the scheduler targets a heap twice the live set (`alive 9.68M, target 19.36M` in the
  log), and peak RSS ran at ~2.2× `alive`. Extrapolated linearly, full scale is ~8 GB live and
  ~17 GB RSS for packed, and ~17 GB live and ~35 GB RSS for naive — naive fits no host in the
  portfolio, packed needs one with more than 16 GB. That is B-22's minimum, not a guess at it.
- **Naive marks 4.5× the objects of packed** (2.68 M against 0.59 M at 1 %), as research §1.2
  predicted; packing is necessary. Whether it is sufficient is what the rows above doubt.
