---
id: research-architecture
title: kesh — architecture research
type: research
status: active
date: 2026-09-24
---

# Research: the architecture of kesh

kesh is an in-memory data store that speaks RESP2, so that `redis-cli`, `memtier_benchmark` and
ordinary Redis client libraries work against it unchanged, and that is built from the portfolio's
own stack — Kotlin/Native on `linuxX64`, the sborka conventions, kore for probes and ordered
shutdown — so that it ships, deploys and is debugged like every other service there. What it offers
over running Redis itself is not speed or features: it is a store the team can build, read and fix
in the language and toolchain it already uses.

This document records **verified facts** (what was read in an artefact, a registry, a source tree or
a measurement, with the address), **decisions** (with the reason and the rejected alternative) and
**risks** (with the machinery that mitigates them). Anything unverified is marked *hypothesis* and
names the backlog item that settles it.

**State on 2026-09-24: the skeleton (B-01) — a linuxX64 binary that answers `PING` — and nothing
else.** The layer documents — features, API, modules — are drafted in a branch (*docs/layer-drafts*)
and reach `main` one by one, as the code they describe lands. The technical brief this research started from lives in that branch as
*research/kesh-technical-brief.md* and is deleted before its last document merges. The brief's §8
(decisions) and §10 (open questions) are carried here, amended where the evidence disagreed.
The brief's §5a — the reference dataset every measurement talks about — is carried as
[appendix A](#appendix-a-the-reference-dataset-the-briefs-5a), so that it outlives the brief;
wherever this document or the backlog writes "§5a", it means that appendix.

---

## 0. What research changed in the brief

Read this table before any backlog item. Each row is argued in the section it names.

| # | The brief said | Research found | Where |
|---|---|---|---|
| 1 | The portfolio's services need a shared store for sessions, counters, leaderboards (§1) | **No service in the portfolio uses Redis for any of that today.** The one Redis consumer found uses it only for `PUBLISH`/`PSUBSCRIBE` — which v1 excludes | §1.1, Q-1 |
| 2 | Capacity (§5a, ~4.3 GB, ~15.8 M keys) is accepted in the last stage (item 17) | That capacity rests on a managed heap an order of magnitude larger than any Kotlin/Native heap measured in the portfolio, where the pause was already growing with the heap. **Measured first now**, before the store is built on it | §1.2, D-17 |
| 3 | D-11: active expiry repeats "while more than 25 % of a sample is expired" | Not what Redis does and not what its current documentation says. The source repeats while more than **10 %** is stale (at default effort); 25 is the slow cycle's **CPU budget** | §1.5, D-11 |
| 4 | `DEL` frees the value before it replies; `UNLINK` replies at once and reclaims later | Under a tracing collector nothing is freed synchronously. Both drop a reference; the observable difference is only in `used_memory`, and that is how it is restated | §1.3, D-15 |
| 5 | (silent) the keyspace is a map | The stdlib `HashMap` on Native rehashes the whole table in one call and cannot give `SCAN` its guarantee; `ByteArray` keys compare by identity. The keyspace is kesh's own table | §1.3, D-12 |
| 6 | (silent) any number of clients | `ktor-network` on Native (3.5.2 and 3.6.0 alike) multiplexes with `pselect` and refuses a descriptor ≥ `FD_SETSIZE` (1024); it also does not retry `EINTR`. A hard ceiling on connections, and a signal hazard | §1.4, D-13, R-3 |
| 7 | D-5: compare replies byte for byte; `conformance` uses a Redis client library (§7) | A client library parses the reply, so it cannot compare bytes. And several replies are legitimately unordered or random. Raw sockets plus per-command normalisers | D-5 |
| 8 | §10: which Redis to pin, and does its licence matter | Nothing in §6 is newer than Redis 7.0. 7.2 is the last BSD line; 7.4 and 8.x are not BSD. Pin 7.2 with `databases 1` | §1.6, §1.7, D-16 |
| 9 | §10: the reference host needs at least 8 GB | The portfolio's measurement hosts have 7.7 GB. The one 16 GB machine is the build box | §1.9, Q-3 |
| 10 | `SIGTERM` … saves if configured, and exits within the chart's grace period | Saving ~4.3 GB is not free; the grace period has to be derived from a measured `SAVE`, not chosen | R-5 |
| 11 | §6: a wrong password answers `-ERR invalid password`; §4: at startup the server refuses connections until the snapshot is loaded | Redis 7.2 answers `-WRONGPASS …`. And while loading, Redis accepts connections and answers `-LOADING …` rather than refusing them — which is right for kesh is open (B-14) | §1.5 |
| 12 | §5a: 2 000 leaderboards of 1 000–100 000 members hold 0.3 GB | Drawn uniformly they hold 1.6 GB. The table is consistent only with mostly small boards: B-03 draws a stratified power law with a mean near 9 700 | appendix A |

---

## 1. Verified facts

### 1.1 Who speaks Redis in the portfolio today

Verified 2026-09-24 by a word-bounded search (`redis`, `lettuce`, `jedis`, `eu.vendeli`, `kreds`,
`redis://`, `:6379`) over the Kotlin, Gradle, TOML, YAML and properties files of every working tree
in both of the portfolio's checkout roots (66 repositories), excluding build output and documentation directories.

| Fact | Where verified |
|---|---|
| The only Redis client in any module is `kompot-realtime-redis`, a multi-instance backend for kompot's realtime bus, on Lettuce `7.6.0.RELEASE` | `youndie/kompot@25133a4!/kompot-realtime-redis/build.gradle.kts`, `youndie/kompot@25133a4!/gradle/libs.versions.toml` |
| It uses **only** `PUBLISH` and one `PSUBSCRIBE "<prefix>:*"` per process, on two pub/sub connections | `youndie/kompot@25133a4!/kompot-realtime-redis/src/main/kotlin/io/github/youndie/kompot/realtime/redis/RedisKompotUpdateBus.kt` |
| It is optional: its consumers default to an in-process bus and switch to Redis only when a `REDIS_URL` is set | kompot's `settings.gradle.kts` comment; one private application's chart and build file |
| No module uses Redis for sessions, counters, rate limits, leaderboards or caching | the same search: every other hit was a comment, a test fixture name or an unrelated substring |
| Lettuce 7.6.0 opens with `HELLO 3`; on an error starting `NOPROTO` (or `ERR … unknown`) it falls back to RESP2 (`AUTH` or `PING`). A failing `CLIENT SETINFO` afterwards is logged at debug and ignored | `redis/lettuce@7.6.0.RELEASE!/src/main/java/io/lettuce/core/RedisHandshake.java` — `tryHandshakeResp3`, `isNoProto`, `applyConnectionMetadataSafely` |

**Consequence 1 — the brief's audience is a projection, not a measurement.** §1 of the brief states a
need ("sessions, rate-limit counters, leaderboards…") that no code in the portfolio has today, and
§5a's reference dataset "models the portfolio's intended use", not a use anyone has. That does not
make kesh wrong, but it means **the capacity target (D-4) is a choice and not a requirement**, and
it should be argued as one. It is the owner's call; see Q-1.

**Consequence 2 — the one real consumer cannot use kesh v1.** Pub/Sub is explicitly out of scope
(brief §2). Either the first consumer is a service not yet written, or Pub/Sub moves into scope.
Q-1 asks which.

**Consequence 3 — the handshake the one real client performs is already compatible.** `HELLO 3`
answered `-NOPROTO …` makes Lettuce fall back to RESP2, which is exactly what the brief prescribes,
and it does not care whether `CLIENT SETINFO` exists. So `CLIENT SETINFO` does not need to be in §6
for Lettuce; answering it with `+OK` anyway is cheap and matches Redis ≥ 7.2 (`since: 7.2.0` in
`redis/redis@7.2.5!/src/commands/client-setinfo.json`).

**This also answers the brief's §10 question 5** ("command coverage beyond §6 that the portfolio's
services already use"): `PUBLISH`, `PSUBSCRIBE`, and the handshake above. Nothing uses `OBJECT`,
`WAIT` or `WATCH`.

### 1.2 The Kotlin/Native heap at the sizes §5a implies

Verified from a portfolio study of the Kotlin/Native collector on Kotlin 2.4.20, `linuxX64`, a
four-core host with one subject resident (the study's repository is private; the figures and
protocol are summarised here so that nothing depends on reading it).

| Fact | Where verified |
|---|---|
| The default collector on 2.4.20 is concurrent mark and sweep (CMS); a binary built without options is byte-identical to one built with `-Xbinary=gc=cms`, and the runtime logs `Concurrent Mark & Sweep GC initialized` | the study's toolchain item: md5 comparison of the binaries, `-Xruntime-logs=gc=info` |
| Pause p99 against live heap `L` at a fixed allocation shape, 100 req/s, three lone starts per point: 14 MB → 2.8–3.0 ms; 128 MB → 4.7–5.2 ms; **512 MB → 9.2–12.3 ms; 1 GB (5.63 M objects marked) → 19.3–34.8 ms** | the study's results, "Open: the pause at large live heaps" |
| The growth is in CMS's **second** pause (end of marking); the first stays near 1 ms. With the same 5.6 M objects marked but no sustained load, the second pause was 0.1–0.4 ms | the same section |
| A second resident process on the host inflated the same binary's pause p99 from ~1.2 ms to 2–4.9 ms; CPU per request was unaffected | the same study, protocol finding |
| The runtime does not read its cgroup memory limit; `GC.targetHeapBytes` is a trigger threshold, not a ceiling, and `GC.autotune` rewrites it. `GC.maxHeapBytes` is the ceiling | `youndie/kore!/kore-core/src/commonMain/kotlin/io/github/youndie/kore/runtime/MemoryBudget.kt`, `youndie/kore!/kore-core/src/nativeMain/kotlin/io/github/youndie/kore/runtime/HeapCeiling.native.kt` |
| **The second CMS pause is the allocator's page bookkeeping**: with the world stopped after marking, every size class's `PageStore::PrepareForGC` walks the `used_` page list to its tail and frees every page the last sweep emptied — both linear in the number of pages | `JetBrains/kotlin@v2.4.20!/kotlin-native/runtime/src/gc/common/cpp/MainGCThread.hpp` lines 56–69; `JetBrains/kotlin@v2.4.20!/kotlin-native/runtime/src/alloc/custom/cpp/PageStore.hpp` line 24; `JetBrains/kotlin@v2.4.20!/kotlin-native/runtime/src/alloc/custom/cpp/AtomicStack.hpp` lines 79–81 |
| Measured (B-19, a quarter of the reference dataset, same heap, interleaved): 16 KiB allocator pages paused 8–10 ms median and 84–139 ms p99; 256 KiB pages 0.8 ms and 8–18 ms, for 1 % more resident memory | `bench/reports/b-19/README.md` |
| **Mutator assists**: when allocated bytes reach `targetHeapBytes` while a collection runs, every Kotlin thread logs "Pausing the mutators until epoch N is done" and waits for that epoch to finish. On by default; off when `GC.autotune` is off or `GC.maxHeapBytes` is finite; no binary option | `JetBrains/kotlin@v2.4.20!/kotlin-native/runtime/src/gcScheduler/common/cpp/GCSchedulerConfig.hpp` — `mutatorAssists()`; `JetBrains/kotlin@v2.4.20!/kotlin-native/runtime/src/gcScheduler/adaptive/cpp/GCSchedulerImpl.hpp` line 79 |
| Measured (B-05, `SET`s growing the keyspace, `-Xruntime-logs=gc=info,gcScheduler=info`): every one of 56 epochs was assisted; the threads waited 1.6 s at 12.3 M marked objects, rising to 4.2 s at 26.6 M, while both stop-the-world pauses stayed under 3 ms. Single-key `SET`s at the same rate: 631 assists, epochs ≤ 5.2 ms | GC logs of the B-05 runs (the item's findings) |
| Resident memory of a Kotlin/Native service follows its **thread** count (a 256 KiB page per size class per thread); `-Xbinary=fixedBlockPageSize=16` removes most of it | `youndie/katcher` issue #58; the mechanism is [KT-89365](https://youtrack.jetbrains.com/issue/KT-89365) |

**Consequence 1 — §5a is outside everything measured, by more than an order of magnitude.** The
largest measured point is 1 GB and 5.63 M marked objects. §5a is ~4.3 GB of *user* data. The
object count matters more than the bytes (marking visits objects), and a rough count is sobering:

*Hypothesis (arithmetic, not measured):* if every key, value, field and member is its own
`ByteArray`, §5a holds at least 2 × 15.8 M objects for keys and top-level values, plus two per hash
field (2 M hashes × 8–20 fields → 32–80 M), one per list item (300 k × 20–200 → 6–60 M), one per set
member (0.5 M × 3–15 → 1.5–7.5 M), and two or more per sorted-set member (skiplist node plus member;
0.3 GB over 2 000 boards is on the order of 10–20 M members). That is **on the order of 100 M
objects** — about twenty times the largest mark measured, where the pause was already 20–35 ms and
growing.

**Consequence 2 — the capacity question cannot be answered by extrapolation and cannot wait for the
last stage.** D-3 (plain Kotlin objects) and D-4 (one process holds §5a) are only compatible if a
§5a-shaped heap is served with acceptable pauses, and nothing measured says whether it is. The
brief itself says D-3 is revisited "only with a measurement" — but schedules that measurement
(item 17) after everything that depends on it is built. D-17 moves a cheap version of it first.

**Consequence 3 — the lever inside D-3 is object count, not bytes.** D-3 already allows compact
encodings inside Kotlin objects ("small collections packed into byte arrays, as Redis does with its
listpack"). Consequence 1 says that is not an optimisation but probably the thing that decides
whether D-3 holds: a hash of 12 fields packed into one `ByteArray` is one object instead of 25.
B-19 measures both shapes.

**Correction found in B-19: the lever is the allocator's page count, not the object count.** The
naive encoding marked 4.7 times the objects of the packed one at the same scale and paused the same;
the pause is the page bookkeeping in the second stop-the-world phase (the facts above), and 256 KiB
pages cut it tenfold where the object count changed nothing. Packing stays — for memory: naive held
twice the live heap of packed, ~35 GB of resident memory at full scale. The mark itself is
concurrent and was never the pause.

**Correction found in B-05: under growth, the mark is the stall.** The last sentence holds for B-19's
load — churn over a heap that does not grow. When writes grow the heap, allocation reaches the
target while a mark is running, and the mutator assists (the facts above) stop every Kotlin thread
until the mark ends: not a stop-the-world pause by the runtime's accounting, but for a client every
command waits, and the wait is the whole mark — linear in live objects, 1.6–4.2 s at 12–27 M. At
§5a's ~100 M objects that extrapolates to well over ten seconds, *hypothesis, not measured*. The
lever is a finite `GC.maxHeapBytes`, which turns the assists off and lets the heap overshoot
instead; the choice is B-23's, R-7.

**Consequence 4 — `maxmemory` is not enforced by the runtime, and the container limit is not seen
by it.** kesh's own accounting (D-10) is the only thing between a write and an OOM kill.
`containerMemoryBudget()` from kore can at least refuse a `maxmemory` that, with the measured
overhead, exceeds the container's limit — at startup and on `CONFIG SET`. *Amended 2026-09-25:* that
function is in kore's `main` but in no published release (0.1.4 has no `MemoryBudget`); the check
is B-26 and waits for one.

**Consequence 5 — pause measurements need one subject per host.** The conformance oracle (a
`redis-server`) may share a host with kesh; a latency measurement may not.

### 1.3 The standard library's map is not a keyspace

Verified against Kotlin `v2.4.20` sources.

| Fact | Where verified |
|---|---|
| `HashMap.ensureCapacity` copies the key, value and presence arrays and calls `rehash`, which rebuilds the whole hash array in one loop over every entry — in the call that crossed the threshold | `JetBrains/kotlin@v2.4.20!/libraries/stdlib/native-wasm/src/kotlin/collections/HashMap.kt` — `ensureCapacity`, `rehash`, `putRehash` |
| Native `ByteArray` does not override `equals`/`hashCode` (identity semantics, inherited from `Any`) | `JetBrains/kotlin@v2.4.20!/kotlin-native/runtime/src/main/kotlin/kotlin/Arrays.kt` — `class ByteArray`, lines 21–85 |
| Redis's `DEL` frees synchronously unless `lazyfree-lazy-user-del yes`; the default is `no` | `redis/redis@7.2.5!/redis.conf` — "LAZY FREEING" |

**Consequence 1 — a keyspace built on `HashMap` stalls the server on growth.** Growing from 8 M to
16 M entries rehashes 8 M entries inside one `SET`. Redis avoids exactly this with incremental
rehashing (two tables, a few buckets moved per operation). The keyspace needs the same, which means
its own table (D-12). D-3 allows this: it is plain Kotlin.

**Consequence 2 — `SCAN`'s guarantee needs bucket-level access.** "Every key present for the whole
iteration is returned at least once" is achieved in Redis by a reverse-binary cursor over a
power-of-two table, which stays valid across a resize. A `HashMap` exposes no buckets, so the
guarantee cannot be built on it. Same conclusion, D-12.

**Consequence 3 — keys need a wrapper or a table that hashes content.** A `HashMap<ByteArray, …>`
compiles and never finds anything that was not the same array instance.

**Consequence 4 — "`DEL` frees before it replies" has no meaning here.** Under a tracing collector,
removing a key drops a reference; the bytes come back when a GC cycle sweeps them, whether the
command was `DEL`, `UNLINK` or `FLUSHALL ASYNC`. What kesh *can* make observable is its own
accounting: `used_memory` falls when the reply is sent. D-15 restates the rules that way.

### 1.4 The TCP transport: `ktor-network` on Kotlin/Native

Verified against `ktorio/ktor` at tags `3.5.2` and `3.6.0`, and Maven Central. kesh pins **3.6.0**
(D-6); every selector fact below holds for both.

| Fact | Where verified |
|---|---|
| `io.ktor:ktor-network-linuxx64` has 3.5.0, 3.5.1, 3.5.2 and 3.6.0; `latest` and `release` are 3.6.0 | `repo1.maven.org/maven2/io/ktor/ktor-network-linuxx64/maven-metadata.xml`, read 2026-09-24 |
| The shared catalog sborka **publishes** (`wip`, `io.github.youndie.sborka:catalog`) pins `kotlin = "2.4.20"`, `ktor = "3.6.0"` (since `f0e9a01`, "take Ktor 3.6.0"), `coroutines = "1.11.0"`; release 0.4.0.91 carries the same | `youndie/sborka@cd1a2bf!/catalog/sborka.versions.toml`; `reposilite.kotlin.website/snapshots/io/github/youndie/sborka/catalog/0.4.0.91/catalog-0.4.0.91.toml` |
| sborka's `gradle/libs.versions.toml` is a different file — the catalog of sborka's **own** build — and still says `ktor = "3.5.2"` | `youndie/sborka@cd1a2bf!/gradle/libs.versions.toml` |
| kore pins its own `ktor = "3.6.0"`, and its research was read in that version | `youndie/kore@54cbc54!/gradle/libs.versions.toml` |
| ktor's `SocketOptions.reuseAddress` defaults to `false`; Redis sets `SO_REUSEADDR` on its listeners | `ktorio/ktor@3.6.0!/ktor-network/common/src/io/ktor/network/sockets/SocketOptions.kt`; `redis/redis@7.2!/src/anet.c` — `anetSetReuseAddr` |

**Correction found while implementing B-01.** This table used to say "the portfolio's catalog pins
`ktor = "3.5.2"`", citing sborka's `gradle/libs.versions.toml`. That is the catalog sborka builds
*itself* with, not the one it publishes; the published `wip` catalog took 3.6.0 in the very commit
cited. So D-6's "pin 3.6.0 in kesh's own catalog as a single override" was a fix for a problem that
did not exist: kesh reads `ktor` from `wip` like everything else and pins nothing (D-6 below).
Whoever reads a sborka version next: `catalog/sborka.versions.toml` is the published one.
| In 3.6.0, `SelectUtilsNix.kt`, `network.def` and `NativeUtils.kt` are byte-identical to 3.5.2; the only native changes are dropped `.toInt()` conversions in `CIOReader.kt`, `TCPSocketNative.kt` and a `@Suppress` in `SignalPoint.kt` | `diff -r` of `ktor-network/nix` and `ktor-network/posix` between tags `3.5.2` (`01c469a`) and `3.6.0` (`111c580`) |
| `ktor-network-linuxx64` 3.6.0 depends on `kotlin-stdlib` 2.3.21, `kotlinx-coroutines-core` 1.11.0, `atomicfu` 0.33.0 (3.5.2: 2.3.21, 1.11.0, 0.32.1) | `repo1.maven.org/maven2/io/ktor/ktor-network-linuxx64/3.6.0/ktor-network-linuxx64-3.6.0.module` |
| The Native selector is one loop around `pselect(maxDescriptor + 1, …)` over `fd_set`s | `ktorio/ktor@3.5.2!/ktor-network/nix/src/io/ktor/network/selector/SelectUtilsNix.kt` — `selectionLoop`; `ktorio/ktor@3.5.2!/ktor-network/nix/interop/network.def` — `selector_pselect` |
| A descriptor `>= FD_SETSIZE` fails a `check(…)` with "File descriptor … is larger or equal to FD_SETSIZE" | `SelectUtilsNix.kt` — `addInterest` |
| A negative `pselect` result is turned into a `PosixException` with no `EINTR` retry | `ktorio/ktor@3.5.2!/ktor-network/posix/src/io/ktor/network/util/NativeUtils.kt` — `Int.check` |
| A signal delivered to that thread therefore kills the process with `PosixException.InterruptedException: pselect failed, EINTR` — observed in the portfolio with an in-process sampling profiler, reproducibly | portfolio measurement (Kotlin/Native service, Ktor CIO, 97 Hz and 997 Hz samplers) |
| kore installs its `SIGTERM`/`SIGINT` handlers with `signal(…)` | `youndie/kore!/kore-core/src/nativeMain/kotlin/io/github/youndie/kore/signal/ShutdownSignalWatch.native.kt` |
| `select`/`pselect` (and `poll`, `epoll_wait`) are never restarted after a signal handler, regardless of `SA_RESTART` | `man7.org/linux/man-pages/man7/signal.7.html`, "Interruption of system calls and library functions by signal handlers" |
| Redis's `maxclients` defaults to 10 000 | `redis/redis@7.2.5!/redis.conf` |

**Consequence 1 — a hard ceiling on connections, below Redis's default.** glibc's `FD_SETSIZE` is
1024, and the listener, the HTTP port and its connections, stdio and the snapshot file all take
descriptors from the same space, against Redis's default `maxclients` of 10 000. kesh must refuse the
connection that would cross its own ceiling with Redis's reply (`-ERR max number of clients
reached`) rather than let the selector throw. D-13.

**Consequence 2 — `pselect` is O(descriptors) per wake-up**, one thread for all of them. At the
reference load (50 connections) that is irrelevant; it is the reason not to promise hundreds of
busy connections without a measurement.

**Consequence 3 — any signal is a hazard, including `SIGTERM`.** `pselect` is never restarted after
a handler, `SA_RESTART` or not, so whichever thread the kernel picks to run kore's handler decides
whether the process survives it. kore's end-to-end shutdown test passes against a real `SIGTERM`;
*hypothesis:* because the handler rarely lands on the selector thread, which is not a guarantee.
R-3 carries it, and it also closes the door on anything that installs a `SIGCHLD` handler — which
is how a fork-based `BGSAVE` would reap its child.

**Answered — 3.6.0 does not differ here** (the `diff` row above). The ceiling, the missing `EINTR`
retry and the single `pselect` loop come with kesh's pin unchanged. The next bump re-reads these
three files before anything else.

### 1.5 The Redis behaviour kesh reproduces

Verified against `redis/redis` sources: the `7.2` branch (head is 7.2.16, `redis/redis@7.2!/src/version.h`), tag
`7.2.5` and tag `8.2.0`. Strings are identical across them unless noted.

| Behaviour | Exact text / value | Where verified |
|---|---|---|
| wrong type | `-WRONGTYPE Operation against a key holding the wrong kind of value` | `redis/redis@8.2.0!/src/server.c` — `createSharedObjects` |
| not authenticated | `-NOAUTH Authentication required.` | same |
| out of memory | `-OOM command not allowed when used memory > 'maxmemory'.` | same |
| missing key (`RENAME`) | `-ERR no such key` | same |
| `HELLO` with an unsupported version | `-NOPROTO unsupported protocol version` | `redis/redis@7.2!/src/networking.c` — `helloCommand` |
| unknown command | `ERR unknown command '<name>', with args beginning with: …` | `redis/redis@8.2.0!/src/server.c` — `processCommand` |
| too many clients | `-ERR max number of clients reached` | `redis/redis@7.2!/src/networking.c` |
| protocol errors | `Protocol error: invalid bulk length`, `… invalid multibulk length`, `… too big inline request`, `… unbalanced quotes in request`, `… expected '$', got '<c>'`, and so on — one string per condition, then the connection is closed | `redis/redis@8.2.0!/src/networking.c` — `processInlineBuffer`, `processMultibulkBuffer` |
| **before `AUTH`, a request is limited**: more than 10 arguments → `Protocol error: unauthenticated multibulk length`; a bulk over 16 384 bytes → `Protocol error: unauthenticated bulk length` | present on the 7.2 branch head and 8.2.0 | `redis/redis@7.2!/src/networking.c` — `processMultibulkBuffer` |
| `INCR`/`INCRBY` overflow | `ERR increment or decrement would overflow` | `redis/redis@7.2!/src/t_string.c` — `incrDecrCommand` |
| `DECRBY k -9223372036854775808` | `ERR decrement would overflow` — a second string the brief does not list | `redis/redis@7.2!/src/t_string.c` — `decrbyCommand` |
| not an integer | `ERR value is not an integer or out of range` | `redis/redis@8.2.0!/src/object.c` |
| `SELECT` out of range | `ERR DB index is out of range` | `redis/redis@8.2.0!/src/db.c` |
| wrong password | `-WRONGPASS invalid username-password pair or user is disabled.` — not the brief's `-ERR invalid password` | `redis/redis@7.2!/src/acl.c` |
| a command while the dataset loads | `-LOADING Redis is loading the dataset in memory` — Redis accepts the connection and refuses the command; the brief refuses the connection | `redis/redis@7.2!/src/server.c` — `createSharedObjects`, `processCommand` |
| wrong arity | `ERR wrong number of arguments for '<command>' command` | `redis/redis@7.2!/src/server.c` |
| **the order of checks**: the command exists, then its arity, then authentication — an unauthenticated unknown command is "unknown command", not `NOAUTH` | `redis/redis@7.2!/src/server.c` — `processCommand`, lines 3876–3924 |
| `POST` or `Host:` as a command closes the connection without a reply (cross-protocol scripting) | `redis/redis@7.2!/src/networking.c` — `securityWarningCommand` |
| inline commands split by `sdssplitargs`; a 64 KB line without its ending is `too big inline request` (`… mbulk count string`, `… bulk count string` for count lines) | `redis/redis@7.2!/src/sds.c`; `redis/redis@7.2!/src/server.h` — `PROTO_INLINE_MAX_SIZE` |
| integers in the protocol parse as `string2ll`: no `+`, no leading zeros, at most 20 characters | `redis/redis@7.2!/src/util.c` — `string2ll` |
| active expiry | 20 keys per loop; the slow cycle may use up to **25 % of CPU**; the loop repeats while the expired share of a sample exceeds `ACTIVE_EXPIRE_CYCLE_ACCEPTABLE_STALE` = **10 %** (lowered further by `active-expire-effort`); `hz 10` | `redis/redis@7.2.5!/src/expire.c` — the `ACTIVE_EXPIRE_CYCLE_*` defines and `activeExpireCycle`; `redis/redis@7.2.5!/redis.conf` |
| the current `EXPIRE` documentation says only that Redis "periodically … tests a few keys at random amongst the set of keys with an expiration"; it no longer states a percentage | `redis.io/docs/latest/commands/expire/`, "How Redis expires keys", read 2026-09-24 |

**Consequence 1 — the brief's error list is incomplete in ways a client notices.** The
unauthenticated limits, `decrement would overflow` and `WRONGPASS` are behaviour a conformance
script will hit on the first run; they go into the feature documents now rather than being discovered as red.

**Consequence 3 (B-05) — one reply cannot be matched on this platform.** `INCRBYFLOAT` computes in
C's `long double` — 80-bit on x86-64 — and prints with `%.17Lf` (`redis/redis@7.2!/src/util.c` —
`ld2string`); Kotlin has no such type. kesh computes in `Double` and prints the shortest decimal
that reads back the same, fixed-point and trimmed: `10.5 + 0.1` agrees (`10.6`), `0.1 + 0.2` does not
(Redis `0.3`, kesh `0.30000000000000004`). Redis itself answers like kesh where `long double` is a
`double`. Recorded as a known divergence in `feature-strings`; the conformance scripts compare only the
agreeing cases, and say so.

**Consequence 2 — D-11 is corrected** (the 25 % is a CPU budget, the repeat threshold is 10 %), and
its note "check the current wording" is answered: the documentation no longer carries a number, so
the source is the authority.

### 1.6 Licences

| Fact | Where verified |
|---|---|
| Redis 7.2.5 is BSD-3-Clause | `redis/redis@7.2.5!/COPYING` |
| Redis 7.4.0 is RSALv2 or SSPLv1 ("Starting on March 20th, 2024…") | `redis/redis@7.4.0!/LICENSE.txt` |
| Redis 8.2.0 is RSALv2, SSPLv1 or AGPLv3; "7.2 and prior releases remain subject to the BSDv3" | `redis/redis@8.2.0!/LICENSE.txt` |
| Valkey is BSD-3-Clause | `valkey-io/valkey@unstable!/COPYING` |
| `memtier_benchmark` is GPL-2.0 | `RedisLabs/memtier_benchmark@master!/COPYING` |
| Ubuntu 24.04 packages `redis-server` 7.0.15 and `valkey-server` 7.2.12 (7.2.13 in `-updates`) | `packages.ubuntu.com/noble/redis-server`, `/noble/valkey-server`, read 2026-09-24 |
| Docker Hub `library/redis:7.2` exists, last updated 2026-09-19 | `hub.docker.com/v2/repositories/library/redis/tags/7.2` |

**Consequence — the licence question dissolves if the oracle is 7.2.** A tool run only in tests is
not distributed, so even AGPL would likely not bind kesh, but that "likely" is a legal opinion this
document is not entitled to. Pinning the last BSD line avoids needing one. Nothing kesh needs is
newer than 7.0 (§1.7).

### 1.7 How new the command set in the brief's §6 is

Verified from the command specifications at `redis/redis@7.2.5!/src/commands/<name>.json` (`since`
and `history`).

| Command or option | Since |
|---|---|
| `EXPIRETIME`, `PEXPIRETIME`; `SET … NX GET` together | 7.0.0 |
| `GETEX`, `GETDEL`, `SMISMEMBER`, `ZMSCORE`; `ZRANGE … BYSCORE/BYLEX/REV/LIMIT`; `LPOP/RPOP` with count; `ZADD GT/LT`; `SET … GET/EXAT/PXAT` | 6.2.0 |
| `HELLO` | 6.0.0 |

**Consequence** — any Redis from 7.0 up is a complete oracle for §6; 7.2 is chosen for the licence
(§1.6) and because it is still published and patched (7.2.16 on the branch).

### 1.8 Toolchain and where it can be built

| Fact | Where verified |
|---|---|
| Kotlin 2.4.20 and ktor 3.6.0 through the shared `wip` catalog; kesh pins neither (see §1.4, D-6) | `youndie/sborka@cd1a2bf!/catalog/sborka.versions.toml` |
| A repository's own `gradle/libs.versions.toml` shadows the shared catalog; one service in the portfolio ran on 2.4.10 for weeks while everything around it assumed 2.4.20 | portfolio incident, 2026-09-23 (the brief's D-7 names the check) |
| kore is not on Maven Central; it resolves from the portfolio's own repository | `youndie/kore!/README.md`, status block |
| The portfolio's measurement hosts reach Maven Central over IPv6 but not GitHub or the portfolio's repository | portfolio host inventory |

**Consequence** — kesh cannot be built on the measurement hosts; it is built on the build machine
and the binary is shipped. This is the same arrangement other native services in the portfolio use.

### 1.9 Hosts

| Fact | Where verified |
|---|---|
| The two measurement hosts have 4 cores and 7 746 MB each | portfolio host inventory, 2026-09-21 |
| The build machine has 20 cores and 16 GB and runs builds | the same |

**Consequence** — the brief's own floor for the reference host ("at least an 8 GB host", §10) is
above the measurement hosts, and the build machine is neither idle nor alone (§1.2, consequence 5).
The reference host is an open question with a concrete constraint (Q-3).

---

## 2. Decisions

The brief's D-1 … D-11 keep their numbers so that the brief, the backlog and this document cite the
same thing. Each carries its state after research.

### D-1. RESP2, default on a new connection, with pipelining and inline commands — *verified*

As in the brief. Confirmed by §1.5 (`-NOPROTO unsupported protocol version`) and by the one real
client's behaviour on that reply (§1.1, consequence 3).

### D-2. A native `linuxX64` binary, not a JVM service — *decision, unchanged*

A JVM target may exist for tests (the `conformance` module runs on the JVM). It is not a
deliverable.

### D-3. Data in ordinary Kotlin objects; no off-heap, no mmap, no custom allocator — *decision, now under test*

Unchanged as a decision, but its revisit condition is now scheduled rather than deferred: B-19
measures a §5a-shaped heap, and B-20 asks the owner, *before* B-19 reports, what "the managed heap
cannot serve the reference dataset" means in numbers. A threshold chosen after the numbers are seen
is chosen to fit them.

The compact-encoding clause of D-3 is where the design effort goes (§1.2, consequence 3): packed
small hashes, sets and lists are expected to decide the verdict, not polish it. *(B-19: they decide
the memory, not the pause — §1.2, correction.)*

**The threshold, set by the owner on 2026-09-24, before B-19 measured anything (B-20):** the managed
heap serves the reference dataset if the collector's **stop-the-world pause p99 is at most 10 ms**.
Read precisely, so that B-19 cannot choose its reading after the fact:

- every stop-the-world pause the collector makes during the measured window counts — for CMS, both
  of its pauses per cycle, not the shorter one only;
- the window is the write churn B-19 applies to the full reference dataset (scale 1.0) once it is
  built; the build itself is not the window;
- one subject on the host (§1.2, consequence 5), with the host named beside the number;
- the verdict takes the better of B-19's two encodings, naive and packed: D-3 holds if either one
  meets the threshold, and then that encoding is the one the store is built with.

What it is set against: the portfolio's own measurement put this platform's end-of-mark pause at
9–12 ms p99 at 512 MB and 19–35 ms at 1 GB (§1.2) — the same 10 ms line that study used for "a pause
worth fixing". The reference dataset is several times larger. The threshold is demanding, and meant
to be.

**Amended by the owner on 2026-09-24, after B-19's first rows: the threshold is no longer a gate.**
The packed encoding measured 35–37 ms p99 at an eighth of the dataset and 60–71 ms at a quarter
(`bench/reports/b-19-preliminary/`), so the 10 ms line would have failed D-3 at any scale worth
having. The owner's decision: *"relax the criterion — keep it as a known limitation, if we understand
where it comes from."* So D-3 stands, the collector pause becomes a known, documented limitation of
kesh, and the condition moves from a number to an explanation: B-19 has to show what the pause is
made of and what it grows with, with a control that could have refuted it. The full-scale figure is
still taken, on the reference host at the last stage (B-17, B-22), and reported per D-9 rather than
gated.

**B-19's verdict, 2026-09-25: D-3 stands** — plain Kotlin objects, the packed encoding, 256 KiB
allocator pages (D-19). The known limitation, explained: the collector's stop-the-world pause is its
page bookkeeping at the end of marking and follows the number of allocator pages. On a quarter of the
dataset with 256 KiB pages it was 1.3–1.5 ms in steady state and 7–18 ms in the first two collections
after a load; the full dataset has about four times the pages (~5–6 ms and ~30–70 ms by the mechanism,
to be measured). Two hypotheses were refuted on the way, each by a control that could have confirmed
it: the objects marked, and the garbage made during marking. `bench/reports/b-19/README.md` has the
runs, the code and the reasoning.

### D-4. One process holds the whole working set — *decision; the size is a choice, not a need*

§1.1 found no current consumer of the capacity, so §5a is a target the owner sets, not a demand the
portfolio places. Q-1 asks the owner to confirm it knowing that.

### D-5. Redis is the oracle, compared byte for byte — *decision, mechanism amended*

Brief: the same command scripts against kesh and a real `redis-server`; §7 says `conformance` uses
"a Redis client library".

Amended: **the comparison runs over raw sockets, not through a client library.** A client parses
the reply — `+OK` and `$2\r\nOK\r\n` both become `"OK"` — so "byte for byte" is impossible through
one. Client libraries are used separately, as smoke tests that the unchanged client works.

And byte equality needs **normalisers**, declared per command in the script and never global:

- unordered replies (`SMEMBERS`, `SINTER`, `KEYS`, `HGETALL` and `HKEYS` beyond the small encoding)
  are compared as multisets;
- random replies (`SPOP`, `SRANDMEMBER`, `RANDOMKEY`) are checked for membership and count, not
  value;
- cursors (`SCAN` family) are compared by the union of what the full iteration returned;
- clocks and identities (`TTL` within a tolerance, `TIME`, `CLIENT ID`, `CLIENT LIST`, `INFO`) are
  checked for shape.

A normaliser is also a way to hide a real difference, so each one is a line in the script that a
reviewer sees, and B-04's acceptance includes a deliberately wrong reply that the harness catches.

**Built in B-04** ([conformance](../services/conformance.md)). `unordered` and `shape` exist; `random` and `cursor`
arrive with `SPOP` (B-08) and `SCAN` (B-10). *B-08:* `random` names its population on the line —
`[random a b c] SRANDMEMBER s 2` — and holds **both** replies to it, so a population written wrong
fails against Redis; a planted `SRANDMEMBER` one member short failed it. B-06 added `pairs`, for a
converted hash's `HGETALL`. *B-10:* `cursor` iterates each server to cursor 0 and compares the unions. Two facts it
surfaced, both read back into the source afterwards: Redis 7.2 **refuses no `SCAN … TYPE` name** —
the refusal is commented out "until Redis 8.0" — and **`ZSCAN` prints a skiplist's scores as
`%.17Lg`** (`scanCallback`), not as `ZSCORE`'s `d2string`: `0.1` is `0.10000000000000001` there. The planted wrong reply (`SELECT 1` → `DB index out of
range`) failed the run at byte 14 of both `SELECT` lines and passed once removed. Two things the
design above did not foresee:
- **`HELLO 3` cannot be compared at all**: Redis switches to RESP3 and kesh refuses it (D-1). It is
  left out of the scripts, and kesh's side is its own test.
- **`[closes]` has to hold both servers to the claim**, not only to agreeing: the harness's own test
  found that two servers which both stayed open agreed on a line saying they close.

### D-6. TCP through `ktor-network` 3.6.0 — *decision (owner, 2026-09-24), verified, with a ceiling* (§1.4, D-13)

The brief named 3.5.2. The owner chose 3.6.0, the current release, which is also what kore is built
and researched against — so kesh and the library that owns its shutdown resolve the same ktor. Its
dependencies are compatible with 2.4.20 (stdlib 2.3.21; coroutines unchanged).

**Amended in B-01:** the shared `wip` catalog already carries 3.6.0 (§1.4, correction), so kesh takes
it from there — `server/build.gradle.kts` reads `wip.versions.ktor` for `ktor-network`, which the
catalog versions but does not list — and `gradle/libs.versions.toml` pins neither `ktor` nor
`kotlin`. Verified in B-01 with `dependencyInsight`: `io.ktor:ktor-network-linuxx64:3.6.0`.

### D-7. Kotlin 2.4.20 through sborka — *verified in B-01*: `buildEnvironment` resolves the Kotlin Gradle plugin 2.4.20, and kesh's catalog has no `kotlin` line

### D-8. `memtier_benchmark` for load, `redis-benchmark` for smoke — *verified* (GPL-2.0, tool only)

### D-9. Performance is reported per release, not gated in v1 — *decision, unchanged*

D-9 governs throughput and latency *as release numbers*. It does not stop B-19 from having a
verdict: B-19 answers whether D-3 holds, which is a design question, not a performance gate.

### D-10. `used_memory` is kesh's own estimate — *measured in B-11 at 1/16 and 1/8 of §5a*

Sharpened by §1.2: the gap between `used_memory` and resident memory contains the runtime, the
per-thread allocator pages, the garbage not yet swept, and the free space a non-moving collector
cannot compact. Only a measurement on §5a says how large it is and whether it is stable over a day
(B-18).

**Built in B-11.** `used_memory` is `MemoryModel`'s sum — each key's entry, key and value at the
64-bit runtime's object and array sizes, and the bucket arrays — kept as a running total: a write
command settles the entries it touched, and every collection keeps its own count, so nothing is
stored per key. `MemoryAccountingTest` holds the total to a recount after every step of a random
workload over all five types.

**Measured** (`bench/reports/b-11/README.md`; the reference dataset loaded through the protocol, the
server idle 45 s): **resident memory is 2.4 × `used_memory` idle and 2.8 × at the load's peak**, at
1/16 and at 1/8 alike, and `used_memory` is 2.3 × the dataset's user bytes. On the build machine, not
a measurement host; full scale needs B-22's host. *Hypothesis*: most of the 2.4 is the collector's
target of twice the live set (§1.2), which holds only if `used_memory` tracks the live heap — the GC
log's `alive` is the check. *Consequence*: a container limit has to hold the peak — `maxmemory` × 2.8
and the process's base — which is the ratio B-26's container check uses.

### D-11. Active expiry samples keys with a TTL on a timer — *corrected; built in B-13*

Brief: repeat "while more than 25 % of a sample is expired".
Corrected to Redis's actual algorithm (§1.5): 20 keys per loop, repeat while more than **10 %** of
a sample is expired, the slow cycle bounded to **25 % of CPU** time, 10 cycles a second. The
"Active expiry" scenario of the brief (≥ 99 % of 100 000 `PX 50` keys gone within 2 s) is
consistent with this and is kept.

**Built in B-13** (`ActiveExpiry`, `Db.expires`): the slow cycle as `activeExpireCycle` has it — the
expiry index walked with the `dictScan` cursor, 20 keys a loop, again while over 10 % expired, 25 ms
a cycle checked every 16 loops, a table under 1 % full skipped. Two things the source did not say:
- **That 1 % rule needs tables that shrink.** Redis shrinks a dictionary under 10 % full from its
  cron; kesh's never shrank (D-12), so after a mass expiry the index stayed large and nearly empty,
  and the cycle skipped it for good — 1 292 of 100 000 keys never went. kesh now shrinks the keyspace
  and the index from the same periodic work, and rehashes 16 384 buckets a call, so a shrink ends
  before the cycle needs the index again (at 1 000 it took 14 cycles, and the 2 s scenario failed).
- **Redis's fast cycle** — run before the event loop sleeps when the stale share is high — is left
  out: a coroutine server has no "before sleep", and the slow cycle alone met the scenario (on the
  running server, 100 000 `PX 50` keys were gone before their 0.76 s load finished).
### D-12. The keyspace is kesh's own hash table, with incremental rehashing — *built in B-05*

Why: §1.3. The stdlib map stalls on growth and cannot give `SCAN` its guarantee.
Rejected: `HashMap<Key, Value>` with a wrapper key — simple, and the 16 M-key growth step alone is
a pause measured in hundreds of milliseconds (*hypothesis*, measured in B-05's growth test).
Price: a data structure the portfolio has to own and test — which D-3 already accepts in principle.
Built as `store/src/commonMain/kotlin/io/github/youndie/kesh/store/keyspace/Keyspace.kt`: bucket
chains, one bucket moved per operation (Redis's `_dictRehashStep`), a per-process hash seed. The bound
is asserted on every step while a table grows to a million keys (`KeyspaceTest`).

### D-13. A connection ceiling below `FD_SETSIZE`, refused the way Redis refuses it — *built in B-02*

Why: §1.4. Without it, the connection that crosses 1024 descriptors throws inside the selector.
The refusal is `-ERR max number of clients reached`, then close.

**The default is measured at startup, not typed** (B-02). After binding, the server counts
`/proc/self/fd` and sets `maxclients` to `FD_SETSIZE` (1024) − open − 32 reserved. The 32 cover what
is not a RESP connection: the connection being refused (it holds a descriptor while it is told),
the HTTP listener and its selector's wakeup pipe (B-15), probe and metrics connections, the snapshot
and its temporary twin (B-14), and a margin. On the build machine that is **986** (6 open at
startup). A `KESH_MAXCLIENTS` above the ceiling is refused at startup, naming both numbers.
Descriptors are handed out lowest-free, so while the count stays under the ceiling every
descriptor does too. Verified with the release binary, `ulimit -n` 1024: of 1 100 connections held
open, 986 were served, 114 refused with Redis's string, all 986 still answered, nothing was logged
as a failure, and the process exited 0 on `SIGTERM`.
Rejected: raising the limit (`FD_SETSIZE` is compiled into glibc's `fd_set`) or writing an `epoll`
transport now (a second network stack before the first one has a user).

### D-14. One thread owns the keyspace — *taken in B-01; confirmed or amended in B-05*

Built as `newSingleThreadContext("kesh-store")` in `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/KeshServer.kt`;
every command, `PING` included, reaches it through one hand-off per read batch (`services/server.md`).

Every command runs to completion on a single store thread; connections parse and write on the I/O
side and hand commands over in order. That gives Redis's semantics — each command atomic, `MSET`
never torn, replies in order — without locks.
Rejected: a lock per stripe of the keyspace — multi-key commands (`MSET`, `RENAME`, `SINTER`,
`SUNION`) then need lock ordering, and the ordering bugs are exactly the kind D-3 wants to avoid.
Price: one core for commands, as in Redis. *Hypothesis:* at the reference load, the store thread is
not the bottleneck before the network is; B-17 reports it.

### D-15. `DEL`, `UNLINK` and `FLUSHALL [ASYNC]` differ only in accounting — *new, amends the brief's §4*

Why: §1.3, consequence 4. All three remove from the keyspace before replying and subtract from
`used_memory` at once; the process's resident memory falls when a collection sweeps, for every one
of them. `UNLINK` and `ASYNC` are accepted for compatibility and documented as synonyms.
Rejected: emulating Redis's synchronous free — there is nothing to call.

### D-16. The oracle is Redis 7.2 with `databases 1` — *new, answers the brief's §10 question 3; owner to confirm*

Why: §1.6 and §1.7. Complete for §6, BSD-licensed, still patched. `databases 1` because kesh has one
database: with Redis's default of 16, `SELECT 1` succeeds on the oracle and fails on kesh, and every
script touching it would diff for a reason nobody wants to read twice.
The oracle's configuration lives next to the harness and is part of what B-04 reviews.
**At B-04**: `conformance/oracle/redis.conf`; every run prints the oracle's version — `redis_version
7.2.16`, the head of the 7.2 line. The owner's confirmation of the pin is still open.

### D-17. Measure the heap before building on it — *new, deviation from the brief's order*

Brief: capacity is measured in stage 6 (item 17), after the store, expiry, eviction and snapshots
are built on D-3.
Decision: B-19, a stand-alone probe that builds a §5a-shaped heap in plain Kotlin objects (and in
packed encodings) at ¼, ½ and full scale and reports heap, resident memory, GC pause p50/p99/max
and mark time under a write churn — before B-05 builds the keyspace.
Why: §1.2. It is cheap (a generator and a map, no protocol) and it is the one measurement that can
invalidate the architecture; everything else in the backlog can only tune it.
What it does not do: replace B-17. B-19 is a heap under synthetic churn; B-17 is the product under
the reference load.

### D-19. kesh is built with 256 KiB allocator pages, not sborka's 16 — *new, B-19*

sborka's `native-service` convention sets `fixedBlockPageSize=16` for every native service: a thread
holds one page per size class for as long as it lives, and in services with many threads and small
heaps 16 KiB pages cut resident memory severalfold. kesh is the opposite — a few threads and a heap
of gigabytes — and the collector's second pause walks and frees the allocator's pages, so it follows
their count (§1.2). Measured on a quarter of the dataset: pause tenfold shorter with 256 KiB pages,
resident memory 1 % higher. `server/build.gradle.kts` sets `allocatorPageSize = 256`.
Rejected: keeping the portfolio's setting for uniformity — it would cost kesh an order of magnitude in
pause for a memory saving it cannot use.
Watch: the per-thread cost comes back with threads. kesh's connections run on `Dispatchers.IO`
(up to 64 threads); at 256 KiB per size class touched, that is on the order of 100 MB at worst —
small beside the heap, and to be checked in B-17's resident-memory figures.

### D-23. Sorted sets: Redis's skiplist with spans, the first level in the node — *new, B-09*

B-09 left the structure to the implementer, with the object count per member recorded, because at
the reference dataset's 10–20 M leaderboard members it matters as much as the complexity. The choice:
**Redis's skiplist with spans** (`zslInsert`, `zslDelete`, `zslGetRank`, `zslGetElementByRank`) for
order, rank and position, logarithmic; a `Keyspace` from member to node for `ZSCORE`. Objects per
member: the node, the member's `ByteArray`, the index entry — and level arrays only on nodes taller
than one level. The level is drawn with p = 1/4, so three nodes in four keep their single level in
their own fields: **3.5 objects per member on average**, where a node with arrays on every level, as
Redis's C layout suggests, is 5.
Small sorted sets — up to 128 members of 64 bytes, `zset-max-listpack-*` — are one sorted
`ByteArray`, and store an integral score as Redis's listpack does, which turns `-0` into `0` in a
packed set only (seen against the oracle). A sorted set's replies are always in its order, so the
encoding never shows otherwise.
Measured (`SortedSetScaleTest`, linuxX64, build machine): `ZRANK` on 1 M members 5.8 µs a call,
on 1 k members 1.4 µs — 4.2×, inside the acceptance's 10×.
Rejected: an order-statistic B-tree with packed leaves — fewer objects still, but a new structure to
get right where Redis's has a record, and its reply order would have to be proved equal to Redis's
by hand instead of by construction.

### D-22. Sets pack under Redis 7.2's listpack limits, and have no intset — *new, B-08*

A set is packed — one `ByteArray` of members — up to 128 members of at most 64 bytes
(`set-max-listpack-entries`, `set-max-listpack-value`, `redis/redis@7.2.5!/src/config.c`), and a
`Keyspace` table after, one-way, like a hash (D-20). Redis has a third encoding kesh does not: a
set of integers up to 512 members is an `intset`, kept sorted, so Redis answers `SMEMBERS` of one in
ascending order. Reproducing it would make every small integer set's order match — at the cost of a
third encoding for an order Redis's documentation does not promise. So set replies of several
members are compared as multisets, which D-5 already planned, and the reference dataset's tags are
short strings, not integers.
Rejected: an intset for the byte-exact comparison it would buy — the comparison would test an
accident of Redis's encoding, not a contract.

### D-21. Lists are chunks of packed items, 8 KiB each — *new, B-07*

A list is a deque of chunks, each one `ByteArray` of length-prefixed items, as Redis's quicklist is a
list of listpacks. A chunk holds up to 8 KiB — `list-max-listpack-size -2`, Redis 7.2's default — and
an item larger than that has a chunk to itself. Unlike a hash's (D-20), a list's encoding cannot show
in a reply: every list reply is in list order. So the size is chosen for memory and for the cost of
rewriting a chunk on a push or a cut, and Redis's own is the one with a record behind it.
The reference dataset's feeds, 20–200 ids of ~10 bytes, are one chunk each: two objects for the
collector instead of up to 200 (research §1.2).
Rejected: `ArrayDeque<ByteArray>` — one object per item, the brief's own objection; and one packed
array per list — a push to the head of a long list would copy all of it.

### D-24. Snapshots: kesh's own format, written beside and renamed, loaded before the listener — *new, B-14*

The file is one stream: a header (`KESHSNAP`, version 1, the time), a record per live key with its
absolute expiry, and an end with the record count and a CRC-32 of every byte before it. `SAVE` writes
to `temp-<pid>.kesh`, `fsync`s, renames over the snapshot and `fsync`s the directory — `rdbSave`'s
order — so a kill mid-save leaves the previous snapshot; the count and the CRC refuse a cut or
damaged file whole, and such a file stops the start (exit 1), never half-served.
The load happens before the listener binds: a client is refused at connect until it ends, as the
brief says. Redis instead accepts and answers `-LOADING`, which Lettuce waits on; behind a
Kubernetes service both look the same (readiness is false), and refusing needs no command-level
state. Rejected: `-LOADING` — a mode every command would have to check, for a difference only a
client connecting straight to the pod could see.
`SAVE` is the only save in v1 (R-6).

### D-25. The collector's mutator assists stay on — *B-23; final by the owner, 2026-09-25*

Research R-7: a write-heavy growth stalls every command for the length of a mark — 8.5 s at 16 M keys
on the build machine. The only lever, a finite `GC.maxHeapBytes`, removes the stall and the brake with
it: the heap passed 8 GB in 29 s of the same load, before half the keys were in. For a store whose
`maxmemory` (B-11) is meant to keep it inside a container, an unbounded overshoot is worse than a
stall. Rejected: assists off. The lever stays in the binary (`KESH_GC_ASSISTS=off`) for measurement.
The measurement was taken on a build machine with another session's builds resident, not on the
quiet host the item asked for; the owner accepted it as final, since no host turns an unbounded
overshoot into a bounded one — a quieter host would only say how soon it reaches the limit. What
would change the answer is a mark fast enough
for the growth — a parallel mark, or a lower trigger (`heapTriggerCoefficient`) — not the switch.

### D-20. Hashes pack under Redis 7.2's listpack limits: 512 fields, 64-byte fields and values — *new, B-06*

B-06 was to take its threshold from B-19's measurement. B-19 gave none: it packed every hash and
found that the pause does not follow the object count (§1.2, correction found in B-19), so packing
is kept for memory, and memory alone does not say where to stop. What does: **reply order.** A packed
hash answers `HGETALL`, `HKEYS` and `HVALS` in insertion order, in Redis and in kesh; a table answers
in its own order in both. With Redis's rule and Redis's defaults (`hashTypeTryConversion`,
`hashTypeSet`; `hash-max-listpack-entries 512` and `hash-max-listpack-value 64` in
`redis/redis@7.2.5!/src/config.c`), a hash is packed in kesh
exactly when it is packed in Redis, so the oracle compares every small hash byte for byte and the
encoding stays invisible, as the brief's §6 wants. The conversion is one-way, as in 7.2.
Large hashes live in the keyspace's own table (D-12), so `HSCAN` (B-10) gets `SCAN`'s guarantee.
Rejected: a threshold of kesh's own (say, by bytes) — every hash between the two thresholds would
answer in a different order from the oracle's, and each comparison would need a normaliser.
Watch: a lookup scans the packed bytes, up to 512 pairs, as Redis's `lpFind` does; whether that costs
more in Kotlin/Native than in C is B-17's to see.

### D-18. `HELLO` answers `server: kesh`, `version: 7.2.0` — *new, B-02*

The brief does not say what `HELLO 2` reports. Redis sends `server: redis` and its own version.
Decision: `server` is `kesh`, so nobody mistakes what they are talking to; `version` is `7.2.0`, the
Redis kesh is held to by the oracle (D-16), because clients that gate features on a version expect
a Redis one. The same constant will feed `INFO`'s `redis_version` (B-15).
Rejected: `server: redis` (a claim kesh is not entitled to make) and kesh's own `0.1.0` as the
version (a Redis client would read it as a Redis older than RESP2 handshakes).

---

## 3. Risks and open questions

**R-1. The collector's pause at §5a scale is unusable.** *Accepted as a known limitation on
2026-09-24 (D-3, amended); explained by B-19 on 2026-09-25 — page bookkeeping, cut tenfold by 256 KiB
pages (D-19). What remains open is its full-scale figure (B-17).* Mechanism: CMS's end-of-mark pause grew
from ~5 ms at 128 MB to 20–35 ms at 1 GB (§1.2); §5a is several times that in bytes and ~20× in
objects. Mitigation: B-19 before B-05 (D-17); packed encodings as the first design lever (D-3); a
threshold set in advance (B-20). Open: if B-19 fails, D-3 or D-4 changes — that is the owner's
decision, taken with the numbers.

**R-2. Resident memory drifts away from `used_memory` over a day.** Mechanism: a non-moving
collector cannot compact; TTL churn (30 % of sessions, all counters) leaves holes in pages that a
different size class cannot reuse. Mitigation: B-18 reports resident memory per hour against
`used_memory`; the chart's memory limit is set from B-11's ratio *plus* B-18's drift, not from
`maxmemory` alone. Open: whether the drift is bounded.

**R-3. A signal kills the server through `pselect`.** Mechanism: §1.4 — no `EINTR` retry in the
selector. Mitigation: the graceful-stop scenario runs repeatedly (not once) under load in B-16; no
in-process signal-based profiler is ever used on kesh (profile with `perf`, from outside); no
`SIGCHLD` handler (which rules out one form of fork-based `BGSAVE`). If B-16 reproduces it, the fix
belongs upstream in ktor — and filing that is the owner's call.

**R-4. The connection ceiling is hit in production.** Mechanism: D-13. Mitigation: `maxclients`
refuses cleanly, `connected_clients` and `rejected_connections` are in `INFO` and in the metrics
(B-15). Open: whether any consumer needs more than a few hundred connections — none exists yet
(§1.1).

**R-5. `SAVE` and startup load of ~4.3 GB take longer than the chart allows.** Mechanism: `SIGTERM`
with save-on-shutdown must finish inside `terminationGracePeriodSeconds`; startup must finish before
the startup probe gives up. Mitigation: B-14 measures `SAVE` and load time on §5a; B-16 derives the
grace period and the startup probe budget from them and writes the arithmetic next to the values.
*Measured in B-14* (`bench/snapshot/check.py`, the build machine, a snapshot on its local disk):

| scale | keys | `SAVE` | snapshot | load at startup |
|---|---|---|---|---|
| 1/16 | 987 625 | 2.3 s (two runs: 2 308, 2 323 ms) | 271 MB | 5.8–6.1 s |
| 1/8 | 1 975 250 | 4.5 s (4 457 ms) | 542 MB | 12.2 s |

Linear in the dataset. At full scale that is *some 36 s to save, 4.3 GB, and 98 s to load* —
arithmetic, not measured: B-22's host. Loading runs three times slower than saving because the heap
grows as it loads and the mutator assists hold it (R-7); B-23 may change the load time.

**R-6. `BGSAVE` without fork** (the brief's §10 question 1). Carried unchanged, with two facts added:
a forked child of a multi-threaded Kotlin/Native process has one thread and a runtime whose other
threads — the collector's included — no longer exist (*hypothesis* that anything but raw writes is
unsafe there), and reaping it with a `SIGCHLD` handler is R-3. Decided in B-14 with its cost
measured.
*Decided in B-14 — no `BGSAVE` in v1* (D-24). Measured with `kesh-fork-probe`
(`bench/src/nativeMain/kotlin/io/github/youndie/kesh/bench/fork/Main.kt`): `fork()` held the parent
27–46 ms at 1/16 of the dataset and 94 ms at 1/8. **The forked child hangs** — at 1/64, 1/16, every
run: its first mutator assist waits for a collection epoch, and the collector's thread does not exist
in a child (the log's last line is the child's `Pausing the mutators until epoch 18 is done`). **With
the assists off** (a finite `GC.maxHeapBytes`, set first thing in the child) the child writes the
same snapshot at `SAVE`'s speed — 271 MB in 1.6 s, 542 MB in 4.7 s — whole by its CRC, and is reaped
by polling `waitpid(WNOHANG)`, no `SIGCHLD` handler (R-3).
Why not ship it: the probe's parent had no other threads; the server has ktor's selector and IO
threads, and a fork taken while one of them holds an allocator or a stdio lock deadlocks the child —
the classic hazard, not exercised here. And the child, with no collector, grows its heap by whatever
it allocates while writing. Both are testable; neither is tested. B-25 carries the fork design with
those two checks. Rejected for v1 also: a pause-and-copy (the whole dataset twice in memory, which
the 2.8 × resident ratio of B-11 cannot afford) — not measured.
**R-7. Writes stall for seconds whenever the keyspace grows.** *Found in B-05.* Mechanism: the
collector's mutator assists (§1.2, correction found in B-05) hold every thread for the length of a
mark once allocation outruns it — a bulk load, a restore, a cache warming after a restart. Mitigation:
B-23 measures the growth with the assists off, which trades the stall for heap overshoot during the
mark; B-11's `maxmemory` has to leave room for that overshoot if they go off. Open: the overshoot's
size at §5a scale.
*Measured in B-23* (`bench/growth/assists.sh`, the build machine with other work
resident, two interleaved pairs): **with the assists, the longest stall of a 16 M-key growth is
8.5 s** (8.54 s both runs, corrected for the machine's clock), peak resident 4.9–6.4 GB, 160 s for
the growth. **Without them there is no stall — and no bound:** the heap passed 8 GB within 29 s, well
short of 16 M keys, where a watchdog stopped it both times; the log shows each epoch ending with more
heap than it began (5.8 GB before, 7.9 GB after, epoch 24). Allocation outruns the concurrent mark,
and nothing slows it. Decision, D-25: the assists stay on (final).

**Q-1. Who is the first consumer, and does Pub/Sub belong in v1?** (B-21, owner.) §1.1: the
portfolio's only Redis user needs `PUBLISH`/`PSUBSCRIBE`, which v1 excludes, and nothing uses the
capacity §5a is sized for. The options as research sees them: (a) keep the scope, and name the
future service that needs §5a; (b) add Pub/Sub, which makes kompot's multi-instance bus the first
consumer — and needs a RESP2 subscribe mode on a connection, which D-14 does not preclude; (c) size
v1 down to what a first consumer needs, which makes R-1 smaller.

**Q-2. What does "the managed heap cannot serve the reference dataset" mean in numbers?**
*Answered by the owner on 2026-09-24 (B-20):* a stop-the-world pause p99 above 10 ms at full scale.
The precise reading is under D-3.

**Q-3. The reference host.** (B-22, owner.) §1.9: the measurement hosts are below the brief's own
8 GB floor, and the build machine is shared. Blocks B-17 and B-18.

**Answered by research** (brief §10): question 3, the oracle pin — proposed in D-16, owner to
confirm; question 5, command coverage in use — §1.1.

---

## 4. What happens next

1. **B-01** — the skeleton, with the `buildEnvironment` check (D-7) and D-14's store thread in
   place.
2. **B-21 and B-20** — two answers from the owner. B-21 decides what v1 is for; B-20 has to exist
   before B-19 reports.
3. **B-19** — the heap probe. It blocks B-05: the keyspace is not built until D-3 has a verdict.
4. **B-02, B-03, B-04** in parallel with B-19: the protocol, the dataset generator, the
   differential harness (with D-5's normalisers and D-16's oracle).

The order and acceptance criteria live in [backlog.md](../../backlog.md).

---

## Appendix A. The reference dataset (the brief's §5a)

Carried verbatim in substance from the brief, because the heap probe (B-19), the generator (B-03),
the scenarios, the load report (B-17) and the soak (B-18) all measure against it, and the brief is
deleted before its branch merges. It is **the capacity v1 is built and accepted for** (D-4) — a
target the owner set, not a demand measured in the portfolio (§1.1). B-03 generates it
deterministically from a seed.

| Part | Keys | Shape | Approximate user data |
|---|---|---|---|
| sessions | 10 000 000 `session:<uuid>` | string, 150–400 B, 30 % with a TTL of 1–24 h | 2.8 GB |
| profiles | 2 000 000 `user:<id>` | hash, 8–20 fields of 10–60 B | 0.9 GB |
| counters | 3 000 000 `rate:<id>:<minute>` | string integer, TTL 2 min, constantly replaced | 0.05 GB |
| feeds | 300 000 `feed:<id>` | list of 20–200 item ids | 0.15 GB |
| tags | 500 000 `post:<id>:tags` | set of 3–15 short members | 0.06 GB |
| leaderboards | 2 000 `board:<name>` | sorted set of 1 000–100 000 members | 0.3 GB |
| **total** | **about 15.8 M keys** | | **about 4.3 GB** |

**The reference load** against it: 80 % reads (`GET`, `HGET`, `HGETALL`, `LRANGE`, `SISMEMBER`,
`ZREVRANGE`), 20 % writes (`SET` with TTL, `HSET`, `INCR`, `LPUSH` plus `LTRIM`, `ZINCRBY`); keys
Zipf-distributed; pipelines 1 and 16 deep, over 50 connections.

**Fixed values for scenarios:** user `1001` (name `Ada`, plan `pro`), board `board:2026-09`, date
2026-10-01.

### How the generator reads the table (B-03)

The generator is `bench/src/commonMain/kotlin/io/github/youndie/kesh/bench/ReferenceDataset.kt`;
`kesh-dataset --seed N --scale F [--out PATH] [--summary-only] [--check]` is its command line. What
it had to decide, because the table does not say:

- **"User data" is keys plus values**, plus 8 bytes per sorted-set score. Values alone cannot be
  meant: the counters would hold 0.01 GB against the table's 0.05.
- **The ranges are not uniform.** Drawn uniformly, most parts miss their total by 5–10 %, so each part
  draws inside its range with the mean its total implies (`SkewedRange`; the arithmetic is beside each
  part in the code).
- **The leaderboards are the one inconsistency.** 2 000 boards of 1 000–100 000 members hold 1.6 GB
  drawn uniformly, not 0.3 GB. The table holds only if most boards are small and a few large — a
  truncated power law with a mean of about 9 700 members, which is what leaderboards look like. It is
  drawn **stratified** (one draw per equal slice of probability, shuffled): a plain sample of 2 000
  from that tail missed the total by +5.0 % at seed 42, which is noise about the seed, not the store.
- **SplitMix64, not `kotlin.random`**, whose sequence is promised only within one Kotlin runtime
  version. `ReferenceDatasetTest` pins the digest of seed 42 at scale 0.0005, and it is equal on the
  JVM and on linuxX64.

Measured, seed 42, scale 1.0 (the same within 0.1 % for seeds 7 and 1 000): 15 802 000 keys,
4 259 648 349 bytes of user data against 4 260 000 000; every part within 0.4 % of the table. The
2 % holds at full scale; at scale 0.01 the 20 leaderboards are −11 %, which is why scaled-down runs
state their scale.

**A baseline from Redis itself** (Redis 7.2, the oracle's image, scale 0.01 loaded with
`redis-cli --pipe`): 42 139 834 bytes of user data held in `used_memory` 73 137 392
(`used_memory_dataset` 61 896 728) — 1.74× and 1.47×. Not a target for kesh; the figure B-11's ratio
will be read beside.

The dataset's code anchor: `bench/src/commonMain/kotlin/io/github/youndie/kesh/bench/`.

