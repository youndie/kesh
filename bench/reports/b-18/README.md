# B-18: 24 hours of the reference load with TTL churn

**Result: no crash, no error, and no drift in resident memory.** Over 24 hours at an eighth of §5a,
kesh's resident memory settled within the first hours and then stayed flat — 2 536–2 550 MiB for the
last fourteen — while `used_memory` fell 5 % as TTL churn expired more keys than the load re-created.
Resident over `used_memory` therefore rose, from 1.84 to 1.91, with the heap neither growing nor giving
back what the expired keys freed (research R-2). The collector's stop-the-world pause did not drift
either: 361 collections an hour, the second pause at 0.8–1.25 ms on average, p99 under 2.5 ms.

**Not a reference host.** The two-host stand of B-17 and B-28: kesh on the 4-core, 7.7 GB subject host
(its idle database container left running, as the owner decided), `kesh-load` on the generator host.
kesh at `main` `84d77ea` (`kesh.kexe` md5 `36382908…`), `kesh-load` md5 `b06b43b7…` — the same
generator as B-17, B-28 and run 1.

## Hour by hour (`run-2/soak/hours.tsv`, a row an hour)

| hour | operations/s | p50 / p99 / p99.9 / max, ms | resident, MiB | `used_memory`, MiB | ratio | keys |
|---|---|---|---|---|---|---|
| 1 | 14 474 | 2.6 / 6.3 / 184 / 340 | 2 646 | 1 401 | 1.89 | 1 836 830 |
| 2 | 14 433 | 2.6 / 6.4 / 184 / 322 | 2 597 | 1 397 | 1.86 | 1 833 187 |
| 6 | 14 641 | 2.5 / 6.4 / 180 / 344 | 2 556 | 1 387 | 1.84 | 1 800 358 |
| 12 | 14 569 | 2.5 / 6.4 / 180 / 375 | 2 538 | 1 372 | 1.85 | 1 750 531 |
| 18 | 14 407 | 2.6 / 6.4 / 180 / 371 | 2 546 | 1 352 | 1.88 | 1 697 911 |
| 24 | 14 310 | 2.6 / 6.4 / 184 / 345 | 2 548 | 1 331 | 1.91 | 1 646 500 |

50 connections at pipeline 1, 24 runs of 3 600 s, **0 error replies** in all of them; 5 threads
throughout. Resident memory each minute (`run-2/rss.txt`, 1 452 samples): 3 111 MiB at the load's peak,
2 640–2 747 MiB in hour 1, 2 554–2 564 in hour 6, **2 536–2 550 from hour 10 to the end**; the 7 GB
watchdog never fired.

## The collector (`run-2/soak/metrics-N.txt`, B-31's metrics)

| hours | collections | missed | second pause: mean | p99 | largest | first pause: mean | marked objects |
|---|---|---|---|---|---|---|---|
| 1 | 383 | 2 | 1.11 ms | ≤ 10 ms | ≤ 50 ms | 0.061 ms | 21.2 M |
| 2–12 | 361 an hour | 0 | 0.92–1.25 ms | ≤ 2.5 ms | ≤ 25 ms (hour 5), else ≤ 5 ms | 0.064–0.075 ms | 21.6–22.1 M |
| 13–24 | 361–362 an hour | 0 | 0.80–0.95 ms | ≤ 2.5 ms | ≤ 5 ms | 0.063–0.078 ms | 21.0–21.9 M |

Per hour, from the difference of two scrapes; p99 and largest are the histogram's bucket bounds, not
exact values. The two missed epochs are in hour 1, which includes the bulk load; none after it.

**The client's tail is not this pause.** p99.9 stays at 176–184 ms and the maximum at 294–375 ms, where
no recorded pause exceeds 50 ms. *Hypothesis:* the collector's mutator assists (research R-7) — a thread
that allocates while a mark runs waits for it, which the pause metrics do not count.

## Run 1, stopped (`run-1-stopped/`)

The first run, on the build before B-31, was stopped by the owner after six whole hours: resident over
`used_memory` 1.91 → 1.87, 0 errors, the same throughput. It agrees with run 2's first six hours.

## What it answers

- **R-2, bounded.** A day of TTL churn did not grow resident memory; the ratio moved because the data
  shrank under a heap that keeps its pages. For the memory limit that is a drift of +4 % on the ratio,
  and the chart's `dailyDriftPercent` becomes 5 (was a 20 placeholder): the limit for 1 GiB of
  `maxmemory` is 3.53 GiB instead of 4.0.

## What it does not show

- **A dataset held at `maxmemory`.** kesh ran with no limit, and the data shrank. At the limit, with
  eviction replacing expiry, the holes would be refilled at the same rate they open; not measured.
- **Pipeline 16.** At pipeline 1 the store thread is far from its ceiling (B-28); a day at pipeline 16
  allocates five times as fast.
- **Full scale** — not measured, by the owner's decision.
