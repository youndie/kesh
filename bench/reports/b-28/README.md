# B-28: the heap under the reference load, before and after kesh's own transport

**Result: at an eighth of §5a, kesh on its `epoll` transport ran 20 minutes of the reference load on
the 7.7 GB host that OOM-killed it on `ktor-network`, peaking at 4.3 GB resident.** At a sixteenth it
serves 97 % of Redis 7.2's operations at pipeline 1, with Redis's tail, in 5 threads instead of 82.

Same stand as B-17 (two 4-core, 7.7 GB hosts; kesh on one, `kesh-load` on the other; Redis 7.2 on the
same host as the reference), same generator (`kesh-load` md5 `b06b43b7…`), kesh at commit `f7a7e8b`
(`kesh.kexe` md5 `1a910d12…`). Not a reference host.

## The eighth: the acceptance (`eighth/`)

| 10 minutes each, 50 connections | pipeline 1 | pipeline 16 |
|---|---|---|
| operations/s | 14 421 | 85 896 |
| p50 / p99 / p99.9 / max, ms | 2.6 / 6.5 / 184 / 326 | 8.1 / 22 / 129 / 251 |
| resident at the end / peak | 2.8 / 3.2 GB | 4.2 / 4.3 GB |
| `used_memory` | 1.27 GB | 1.47 GB |
| threads | 5 | 5 |

Sampled every 5 s (`eighth/b28-rss.txt`): never above 4.2 GB, no watchdog. `used_memory` grows over
the twenty minutes because the load adds leaderboard members and re-creates expired counters.

## The sixteenth, before and after (`series-1-16/`, and B-17's `series/`)

| 50 connections | kesh on ktor (B-17) | kesh on `epoll` | Redis 7.2 |
|---|---|---|---|
| pipeline 1, operations/s | 6 410–6 659 | 14 249–14 683 | 14 640–15 126 |
| pipeline 1, p50 / p99 | 5.2–5.4 / 12–13 ms | 2.6–2.7 / 8–9 ms | 2.6 / 7 ms |
| pipeline 1, p99.9 | 1.0–1.15 s | 0.10–0.12 s | 0.10 s |
| pipeline 16, operations/s | 55 944–57 109 | 77 467–79 637 | 134 932–135 763 |
| pipeline 16, p99.9 / max | 1.2–1.3 / 1.8 s | 0.8–1.05 / 0.9–1.45 s | 0.11 / 0.15 s |
| store thread (one core = 100 %) | 24 % / 48 % | 60 % / 73–77 % | — |
| peak resident / `used_memory` | 3.3 / 0.63 GB (5.3 ×) | 2.07 / 0.64 GB (3.2 ×) | 0.50 / 0.48 GB |
| threads | 82 | 5 | — |

**What limits kesh now.** At pipeline 1, nothing kesh-side: it matches Redis, whose limit on this
stand is the generator and the link. At pipeline 16, **the store thread**: three quarters of a core,
the rest of the process idle — the design's own ceiling (research D-14), no longer the transport's.
The tail at pipeline 16 is still near a second: the collector's assists (research R-7) at 80 000
operations a second. The generator's own floor is about 0.1 s (it shows against Redis), so tails
below that are not resolved.

## Why (from the build machine, `bench/load/runaway.sh`)

A `SET` at pipeline 1 allocated 28 KB on `ktor-network` and 1.4 KB on kesh's transport (the
collector's log; 300 000 requests each): kotlinx-io on Native does not pool its 8 KB segments
(research D-31). With kesh pinned to 4 cores, the reproduction at an eighth peaked at 5.45 GB before
and 3.14 GB after, alive steady at 1.3 GB.

## And the graceful stop, again (`drain-runs.txt`)

B-16's scenario on the new transport, 10 runs on kind: 10 of 10 — 200 connections, about a million
replies a run, no truncated reply, the saved counter equal to the replies received, drains of 10–15 ms.
