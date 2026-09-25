# B-25: BGSAVE through fork, under load

**Not the reference host.** The build machine (`host.txt`): 20 cores, 16 GB, other projects' builds
resident; kesh in a scope of its own with a ceiling of 64 tasks and 8 GB, so a fork that ran away
could not take the host with it. A memory question and a hang question, not a latency one — one host
is enough for both (research §1.2, consequence 5 is about pauses).

## Result

| | 1/64, old writer | 1/64, final | 1/8, old writer | 1/8, final |
|---|---|---|---|---|
| `BGSAVE`s asked | 500 | 500 | 5 | 5 |
| ended ok / failed / hung | 500 / 0 / 0 | 500 / 0 / 0 | 4 / 1 / 0 — the child OOM-killed at the scope's 8 GB | 5 / 0 / 0 |
| median save | 1.15 s | 0.85 s | 5.24 s | 5.69 s |
| `fork()` held the parent, median / max | 11 / 25 ms | 10 / 15 ms | 48 / 60 ms | 59 / 85 ms |
| `used_memory` | — | 0.17–0.28 GB | 1.22–1.24 GB | 1.22–1.23 GB |
| parent resident before the first save | 0.51 GB | 0.51 GB | 3.24 GB | 3.17 GB |
| child's private dirty, peak | 0.64 GB | 0.54 GB | 4.24 GB | 1.61 GB |
| parent and child together (Pss), peak | 1.44 GB | 1.32 GB | 8.09 GB | 5.66 GB |
| the last snapshot loads | 246 903 keys | 246 898 keys | 1 975 250 keys | 1 975 250 keys |

Per save: `500-at-1-64-old-writer.txt`, `500-at-1-64-final.txt`, `eighth-old-writer.txt`,
`eighth-new-writer.txt`. The old writer is `Snapshot.write` before `da57c0f` (binary md5 `789ad031…`,
built from `80a971d` with `198488c`'s reap before the kill); the final binary has `da57c0f`'s writer
(md5 `f4464753…`).

**No child hung and none failed on its own**, in 1 010 forks from a server under load. The one
failure is the scope's memory ceiling, which the old writer crossed and the final one did not.

**The writer's copies were most of the child.** Every element of a packed collection was copied out
(`Packed.read`) before it was written, and lists and sorted sets went through `range()` lists and
`Pair`s; a forked child has no collector, so it kept every copy. Written from where the store keeps
the bytes, the child's private memory at 1/8 fell from 4.2 to 1.6 GB, and the two processes together
from 8.1 to 5.7 GB. At 1/64 it hardly moved (0.64 to 0.54 GB): there a save takes under a second and
the child's private pages are mostly what the parent copied, below.

**What remains is the parent's.** The parent's private dirty memory equals its resident memory within
two saves: the collector's mark and sweep write into every live object (research R-6), so an epoch
during a save copies nearly the whole heap, and the load's writes add to it. That is 1.4 ×
`used_memory` beyond the server's own, 4.6 × in all at 1/8 — above the chart's 3.3 ×. B-29.

**The parent's resident memory grows with the load, not with the saves.** Over the final 500,
`used_memory` went from 175 to 275 MB — the load keeps adding to lists and sets — and the parent's
resident memory from 513 to 795 MB: 3.1 × to 2.9 ×, inside B-28's 2.9–3.2 × for this load with no
saves.

## How

`bench/fork/bgsave.sh <scale> <count> <memory ceiling>` on one host: kesh loaded at the scale through
RESP, `kesh-load` at pipeline 16 for as long as the saves take, and `bench/fork/bgsave.py` asking for
the saves one after another. Each save is waited for until `rdb_bgsave_in_progress:0`, then must show
`rdb_last_bgsave_status:ok` and a `LASTSAVE` that moved; the next begins after the second has turned,
since `LASTSAVE` is in seconds. A child still running after 120 s would be a hang: its kernel stack
printed, then killed. Every 50 ms during a save, `/proc/<pid>/smaps_rollup` of the parent and the
child: resident, proportional (Pss) and private dirty. Then kesh is stopped and started from the last
snapshot — a start that loads it is a whole snapshot (count and CRC, research D-24). A watchdog kills
kesh if the host's available memory falls under 1.5 GB. It was added after the first 1/8 run, which
had to be stopped by hand before any save: another project's build had the host at 188 MB available
and swapping.

## What it does not show

- **The reference host and full scale.** 1/8 was the most this host held beside its other work.
- **A save while the dataset grows fast.** The load here adds slowly; a bulk load during a save would
  make the parent's copy-on-write follow it.
- **The lock hazard for threads kesh does not have.** Five threads since B-28; a dependency that adds
  one that takes the allocator's or stdio's locks brings the hazard back, and this run is what would
  find it.
