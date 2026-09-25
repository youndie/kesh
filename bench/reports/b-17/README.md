# B-17: the reference load on a two-host stand

**Not a reference host, not the reference dataset.** Two 4-core, 7.7 GB hosts; kesh on one, the load
generator on the other, a private network between them (RTT 0.6–2.3 ms). A sixteenth of §5a
(988 787 keys loaded), because an eighth did not survive the load (below). Redis 7.2 on the same
host through the same generator is the reference point. kesh at commit `943fddd`
(`kesh.kexe` md5 `6497f056…`), generator `kesh-load` md5 `b06b43b7…`.

## Result

| 50 connections, §5a's mix | kesh, pipeline 1 | kesh, pipeline 16 | Redis 7.2, pipeline 1 | Redis 7.2, pipeline 16 |
|---|---|---|---|---|
| operations/s, round 1 / round 2 | 6 659 / 6 410 | 55 944 / 57 109 | 15 406 / 14 888 | 135 676 / 132 492 |
| p50 / p99, ms | 5.2–5.4 / 12.3–13.1 | 10.0–10.2 / 19.5–20.0 | 2.4–2.6 / 6.4 | 4.4–4.5 / 84–86 |
| p99.9 / max, s | 1.03–1.15 / 1.77 | 1.21–1.31 / 1.82 | 0.10 / 0.21 | 0.11 / 0.16 |
| store thread (one core = 100 %) | 23–24 % | 48 % | — | — |
| whole process / subject host busy | 1.9 cores / 55 % | 1.9 cores / 56 % | — / 14 % | — / 18 % |
| resident memory | 2.3–2.8 GB, peak 3.3 GB | | 0.50 GB | |
| `used_memory` | 0.61–0.63 GB | | 0.48 GB | |
| threads | 82 | | | |

Per operation, per run: `series/*.txt`; host readings: `series/*.host.txt`.

**What limited kesh: neither the store thread nor the network — kesh's own I/O layer.** The store
thread used a quarter of a core at pipeline 1 and half a core at pipeline 16. The network carried 2.3
times as much for Redis, through the same generator and link. The host was half idle. The process's
1.9 cores went mostly to ktor's I/O pool — some 70 threads at about 2 % each — and to the hand-offs
between them and the store thread: every command batch crosses to the store thread and back
(research D-14). kesh serves 43 % of Redis's operations at pipeline 1 and 42 % at pipeline 16.

**The tail is the collector's, some two hundred times the median.** p99.9 of 1–1.3 s against
Redis's 0.1 s through the same generator. The generator's own collector sets a floor near 0.1 s — it
shows against Redis — so kesh's excess is kesh's. The runtime-logs build shows the mechanism: an epoch
under load holds every mutator for its mark (research R-7) — 0.97 s in the diagnostic run.

## At an eighth, kesh was killed

With an eighth of §5a (`used_memory` about 1.2 GB), kesh was OOM-killed at 5.8 GB resident within a
minute of the load, on a host with 5.3 GB available. The collector's log: an epoch under load kept the
20.9 M objects it found and swept 13 924 — what was allocated during its mark counted as alive — and
the next target became 5.17 GB. kesh allocates about 1.1 M objects a second at 5 000 operations a
second. **B-28** takes this; until it is closed, no capacity claim above a sixteenth holds, and B-16's
memory limit (2.8 × `maxmemory`, from B-11's idle ratio) is too low under traffic: here the peak was
5.3 × `used_memory`.

## How

`bench/load/stand.sh` from a third machine; the subject host's other resident process (another
project's database container) stopped for the runs and started after. kesh first: load through RESP, a 20 s warm-up round (discarded),
then two rounds of pipeline 1 and pipeline 16, each 10 s of warm-up and 60 s measured. Then Redis 7.2
(`redis:7.2`, no persistence) the same way. `kesh-load` rebuilds the dataset's keys from the seed and
drives §5a's mix — 80 % reads, 20 % writes, Zipf 0.99 keys, the weights in `bench/…/load/Workload.kt` —
closed loop: each connection sends its pipeline and waits for the replies.

## What it does not show

- **The reference host and dataset.** 7.7 GB holds a sixteenth under this load; the numbers do not
  extrapolate, least of all the memory.
- **Bucket-level histograms for the series.** The script wrote them to a directory that did not exist
  — the stack traces at the end of each `series/*.txt` are that — so the committed record is the
  generator's per-operation percentiles. Fixed in the script; `redis-mem.raw` from the memory re-run
  shows the format.
- **Redis's memory in the series** (the script asked the wrong address); re-measured after it at the
  same scale: 0.48 GB `used_memory`, 0.50 GB resident.
- **An open-loop load.** A closed loop slows down with the server, so it cannot show queueing beyond
  what 50 connections × the pipeline hold.
