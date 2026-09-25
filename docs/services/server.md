---
id: server
title: "server — the binary: TCP listener, connections, dispatch, lifecycle"
type: service
status: active
module: server
tech_stack: [Kotlin/Native linuxX64, epoll, kore 0.1.5]
owner: unassigned
depends_on: [resp, kore]
publishes: [native binary kesh]
---

# server

## 1. Responsibility

The process: accepts TCP connections, owns each connection, executes parsed commands in order on
the store thread — which is also its only I/O thread — and writes the replies back, and stops in
order on `SIGTERM` through kore.

**Built (B-01, B-02):** the listener, connections, the store thread, the client registry, every
command of [endpoint-connection](../api/endpoint-connection.md), `requirepass`, the `maxclients`
ceiling, and kore's shutdown plan with the listener as its drain participant. **Since B-28 the
transport is kesh's own `epoll` loop** (research D-31), not `ktor-network`.

***Target*:** the HTTP port with probes and metrics (B-15); snapshot load and save (B-14); `CONFIG`
(B-11); `INFO` (B-15). The data commands go to the `store` module (B-05).

**Deliberately does not:** implement any data command, or accept more connections than its descriptor
limit allows (research D-31).

## 2. API contracts

* [endpoint-connection](../api/endpoint-connection.md), [endpoint-server](../api/endpoint-server.md)
  (partly) and [endpoint-http](../api/endpoint-http.md) are implemented here.
* **Auth tier:** one shared password (`KESH_PASSWORD`, Redis's `requirepass`); before `AUTH` only
  `AUTH`, `HELLO` and `QUIT` are answered.

## 2a. Code anchors

| File | What is there |
|---|---|
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/Main.kt` | `main`: start, then kore's `runUntilSignal`: readiness off, then the listener as the drain |
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/KeshServer.kt` | the listener, the store thread, the connection scope, the drain |
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/net/EventLoop.kt` | the loop: `epoll`, an `eventfd` to wake it, the store thread's coroutine dispatcher |
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/net/RespConnection.kt` | read → parse → execute → write, per connection, on the loop; a buffer kept per connection |
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/net/HttpConnection.kt` | the HTTP port on the same loop: `GET`, one request per connection |
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/net/Sockets.kt` | non-blocking listen and accept, `SO_REUSEADDR`, `TCP_NODELAY`, the descriptor limit |
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/pubsub/PubSub.kt` | Pub/Sub's registry on the store thread: confirmations, delivery, the counts (B-27) |
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/command/CommandDispatcher.kt` | the command table and the connection commands; store thread only |
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/client/Clients.kt` | the client registry and the `maxclients` count; store thread only |
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/info/Info.kt` | `INFO`'s six sections |
| `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/ConnectionScenariosTest.kt` | the feature's scenarios through a real socket |
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/ServerConfig.kt` | environment |
| `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/KeshServerTest.kt` | through a real socket: PING, pipelining, protocol error, drain, restart |
| `server/build.gradle.kts` | linuxX64 only; `sborka.native-service` names the binary `kesh` |

## 3. How it is built

* **One thread reads, executes and writes** (research D-14, D-31). The `EventLoop` waits on `epoll`;
  a readable connection is read into a buffer it keeps for its life, every complete command is
  executed where it is read, and the replies are written at once — `EPOLLOUT` is asked for only
  while the socket has not taken them all. Order and atomicity are structural: nothing else touches
  the data. Until B-28 connections read on ktor's I/O pool and handed each batch to a separate store
  thread and back; that transport allocated 28 KB a request (research D-31).
* **The drain stops accepting, then lets each connection write what it was told** (B-16): a
  connection with nothing pending closes at once — no command of it is ever half done, because
  commands run where they are read; one that is still writing closes when it has; one that does not
  read its replies is closed after 5 s. Then the save, if configured.
* **A start that cannot go on says why and exits 1** (`StartupFailure`, B-24): a port another
  process listens on stops the start with `kesh: could not listen on <host>:<port>: …` — not an
  uncaught exception and a core dump.
* **The snapshot is loaded before the listener binds** (research D-24): a client is refused at
  connect until the load ends; a snapshot that cannot be read stops the start with one line and exit
  status 1 (the same `StartupFailure`). `SAVE` and `LASTSAVE` run on the store thread (`persistence/`).
* **`BGSAVE` forks the store thread** (B-25, research R-6). The child — one thread, the collector's
  gone — turns the mutator assists off, closes the listeners and writes; it never returns into the
  loop, which would be a second server on the same sockets. The periodic work collects it; the drain
  kills a running one before its own save.
* **Periodic work runs on the loop too, ten times a second** — Redis's `serverCron` for the data
  (B-13): the active expiry cycle, then the tables' resizing, then collecting a finished `BGSAVE` child. The loop is a coroutine dispatcher, and
  this is a coroutine on it, so it runs between commands, never inside one, and stops with the drain.
* **Clients live on the store thread too.** Registration, `CLIENT LIST` and `CLIENT KILL` all run
  there, so the `maxclients` count and the registry are exact without a lock: an accepted socket is
  registered at once, or told `-ERR max number of clients reached` and closed.
* **One command at a time before `AUTH`.** The parser applies Redis's unauthenticated limits before
  `AUTH`, and `AUTH` changes that for the very next command of the same read.
* **The default `maxclients` is Redis's rule** (research D-31, amending D-13): 10 000, lowered to the
  descriptor limit (`RLIMIT_NOFILE`) less 32 reserved. A configured value above it is refused at
  startup, naming both numbers.
* **A descriptor's failure stays in the descriptor.** A handler that throws is reported and its
  descriptor dropped; the loop carries on, since an uncaught exception on its thread would end the
  process.
* **`SO_REUSEADDR` and `TCP_NODELAY`, as Redis sets them** (`redis/redis@7.2!/src/anet.c`). Without the
  first, a restart inside `TIME-WAIT` died at startup with `EADDRINUSE`.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Module | [resp](resp.md) | parsing and writing |
| Library | kore-core 0.1.5 | `runUntilSignal`, the shutdown plan, the probes' gates, `containerMemoryBudget()` (B-26); from the portfolio's repository — 0.1.5 is not on Maven Central |
| Library | kotlinx-coroutines, from the shared `wip` catalog | the loop is a coroutine dispatcher; the tests' clients use `ktor-network` |

## 5. Infrastructure and deploy

* **Binary:** `kesh.kexe`, the release executable of the linuxX64 target; `stageNativeImage` copies it
  as `kesh` into the module's `native-image` build directory, with the list of shared libraries it
  asks for (glibc only).
* **Image, chart, probes, metrics:** *target* — B-15, B-16.

## 6. Local setup

On the Linux build machine (the repository is synced there; see `CLAUDE.md`):

```bash
./gradlew :server:linkReleaseExecutableLinuxX64
KESH_PORT=6379 server/build/bin/linuxX64/releaseExecutable/kesh.kexe
docker run --rm --network host redis:7.2 redis-cli -p 6379 ping
```

## 7. Configuration

`ServerConfig.kt` is the list. Today: `KESH_PORT` (6379), `KESH_BIND` (`0.0.0.0`), `KESH_PASSWORD`
(none), `KESH_MAXCLIENTS` (derived), `KESH_PROTO_MAX_BULK_LEN` (512 MB),
`KESH_CLIENT_QUERY_BUFFER_LIMIT` (1 GB), `KESH_MAXMEMORY` (0, no limit; B-11),
`KESH_MAXMEMORY_POLICY` (`noeviction`) and `KESH_MAXMEMORY_SAMPLES` (5; B-12), `KESH_DIR` (`.`) and
`KESH_DBFILENAME` (`dump.kesh`; B-14), `KESH_GC_ASSISTS` (`on`; B-23), `KESH_HTTP_PORT` (8080, `off` for
none; B-15), `KESH_SAVE_ON_SHUTDOWN` (`off`), `KESH_SHUTDOWN_DRAIN_SECONDS` (15) and
`KESH_TERMINATION_GRACE_SECONDS` (undeclared; B-16) — kore refuses at startup a plan that does not fit
the declared grace period, and kesh exits 1 with its message; `KESH_RESIDENT_PEAK_RATIO_TENTHS` (33;
B-26) — a `KESH_MAXMEMORY` that, at that ratio, the container's memory limit cannot hold stops the
start the same way. The printed configuration never shows the password.

**`KESH_` in upper case, decided in B-01.** The brief spelled the prefix `kesh_`, which read as a
working-name substitution rather than a decision; environment variables are conventionally upper
case.

## 8. Quirks

* **Signals are no longer a hazard to the transport** (research R-3, closed by D-31): the loop
  retries `epoll_wait`, `read` and `write` on `EINTR`. Until B-28, `ktor-network`'s `pselect` did not,
  and a signal on its thread killed the process.
* **A stop takes five seconds even with nothing to announce.** kore's announce stage waits its
  default `preDrainWait` (5 s) for readiness to propagate, and kesh has no readiness gate until B-15.
  Harmless now; B-16 sizes it with the rest of the grace period.
* **A peer that resets is a closed connection, not a failure.** Reporting it put 318 lines in the
  log during B-02's 1 100-connection flood; Redis logs it at verbose level only.
* **Out of descriptors is survivable.** An `accept` that fails (EMFILE under a low `ulimit -n`) is
  logged and retried after 100 ms, as Redis keeps listening; it used to end the accept loop.
* **The selector is O(descriptors) per wake-up**, one thread for all connections. Irrelevant at the
  reference load; the reason not to promise hundreds of busy connections without a measurement.
