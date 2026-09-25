---
id: server
title: "server — the binary: TCP listener, connections, dispatch, lifecycle"
type: service
status: active
module: server
tech_stack: [Kotlin/Native linuxX64, ktor-network 3.6.0, kore 0.1.4]
owner: unassigned
depends_on: [resp, kore, ktor-network]
publishes: [native binary kesh]
---

# server

## 1. Responsibility

The process: accepts TCP connections, owns each connection, hands parsed commands to the store
thread in order and writes the replies back, and stops in order on `SIGTERM` through kore.

**Built (B-01, B-02):** the listener, connections, the store thread, the client registry, every
command of [endpoint-connection](../api/endpoint-connection.md), `requirepass`, the `maxclients`
ceiling below `FD_SETSIZE` (research D-13), and kore's shutdown plan with the listener as its drain
participant.

***Target*:** the HTTP port with probes and metrics (B-15); snapshot load and save (B-14); `CONFIG`
(B-11); `INFO` (B-15). The data commands go to the `store` module (B-05).

**Deliberately does not:** implement any data command, or accept more connections than its selector
can watch (research D-13, from B-02).

## 2. API contracts

* [endpoint-connection](../api/endpoint-connection.md) is implemented here. `endpoint-server` and
  `endpoint-http` are drafted in the *docs/layer-drafts* branch and arrive with B-11, B-14 and B-15.
* **Auth tier:** one shared password (`KESH_PASSWORD`, Redis's `requirepass`); before `AUTH` only
  `AUTH`, `HELLO` and `QUIT` are answered.

## 2a. Code anchors

| File | What is there |
|---|---|
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/Main.kt` | `main`: start, then kore's `runUntilSignal` with the listener as the drain |
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/KeshServer.kt` | the listener, the store thread, the connection scope, the drain |
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/connection/Connection.kt` | read → parse → execute on the store thread → write, per read |
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/command/CommandDispatcher.kt` | the command table and the connection commands; store thread only |
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/client/Clients.kt` | the client registry and the `maxclients` count; store thread only |
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/client/DescriptorCeiling.kt` | the default `maxclients`, from the descriptors open at startup |
| `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/ConnectionScenariosTest.kt` | the feature's scenarios through a real socket |
| `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/ServerConfig.kt` | environment |
| `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/KeshServerTest.kt` | through a real socket: PING, pipelining, protocol error, drain, restart |
| `server/build.gradle.kts` | linuxX64 only; `sborka.native-service` names the binary `kesh` |

## 3. How it is built

* **One store thread executes every command** (research D-14, taken in B-01). A connection reads
  and parses on the I/O dispatcher; every command completed by one read is executed in **one**
  hand-off to the store thread (`newSingleThreadContext("kesh-store")`), and the replies are written
  in one write. Order holds because a batch runs in order and the next read waits for the write.
  Even `PING` goes through the hand-off, so the atomicity guarantee is structural, not something the
  data commands add later.
* **The drain cancels, then closes.** `stop()` cancels the accept loop and the connections, then
  closes the listener. The other order let `IOException: Accept failed` escape while the socket did
  not yet report itself closed, and an uncaught coroutine exception terminates a Kotlin/Native
  process — 4 of 8 test runs before the fix, 0 of 20 after. Letting in-flight commands finish and
  their replies flush first is B-16's graceful stop.
* **A start that cannot go on says why and exits 1** (`StartupFailure`, B-24): a port another
  process listens on stops the start with `kesh: could not listen on <host>:<port>: …` — not an
  uncaught exception and a core dump.
* **The snapshot is loaded before the listener binds** (research D-24): a client is refused at
  connect until the load ends; a snapshot that cannot be read stops the start with one line and exit
  status 1 (the same `StartupFailure`). `SAVE` and `LASTSAVE` run on the store thread (`persistence/`).
* **Periodic work runs on the store thread too, ten times a second** — Redis's `serverCron` for the
  data (B-13): the active expiry cycle, then the tables' resizing. It is a coroutine in the
  connection scope that hops to the store thread, so it runs between commands, never inside one, and
  stops with the drain.
* **Clients live on the store thread too.** Registration, `CLIENT LIST` and `CLIENT KILL` all run
  there, so the `maxclients` count and the registry are exact without a lock: an accepted socket is
  registered in one hand-off, or told `-ERR max number of clients reached` and closed.
* **Batch when authenticated, one at a time before.** The parser applies Redis's unauthenticated
  limits before `AUTH`, and `AUTH` can change that between two commands of one read — so until the
  client is authenticated, each command is parsed only after the previous one ran.
* **The default `maxclients` is measured, not typed** (research D-13). After binding, the server
  counts `/proc/self/fd` and sets the ceiling to `FD_SETSIZE` − open − 32 reserved: 986 on the build
  machine. A configured value above it is refused at startup, naming both numbers.
* **A connection's failure stays in the connection.** The connection scope has a
  `CoroutineExceptionHandler` that reports and carries on; without it one failing socket would end
  the process for the same reason.
* **`SO_REUSEADDR`, as Redis sets it** (`redis/redis@7.2!/src/anet.c` — `anetSetReuseAddr`). A
  connection the server closed leaves the server's port in TIME-WAIT; ktor's default is off, and
  without the flag a restart inside that window died at startup with `EADDRINUSE`.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Module | [resp](resp.md) | parsing and writing |
| Library | kore-core 0.1.4 | `runUntilSignal`, the shutdown plan |
| Library | `ktor-network` 3.6.0, from the shared `wip` catalog | TCP (research D-6) |

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
`KESH_CLIENT_QUERY_BUFFER_LIMIT` (1 GB). The printed configuration never shows the password.

**`KESH_` in upper case, decided in B-01.** The brief spelled the prefix `kesh_`, which read as a
working-name substitution rather than a decision; environment variables are conventionally upper
case.

## 8. Quirks

* **Any signal is a hazard** (research R-3). `pselect` is never restarted after a signal handler, and
  `ktor-network` does not retry `EINTR` — the process dies with `PosixException.InterruptedException`.
  Do not profile kesh with an in-process, signal-based sampler; use `perf` from outside. Do not
  install a `SIGCHLD` handler. Ten `SIGTERM`s with a client connected all exited 0 in B-01, which is
  consistent with the handler rarely landing on the selector thread and proves nothing stronger.
* **A stop takes five seconds even with nothing to announce.** kore's announce stage waits its
  default `preDrainWait` (5 s) for readiness to propagate, and kesh has no readiness gate until B-15.
  Harmless now; B-16 sizes it with the rest of the grace period.
* **A peer that resets is a closed connection, not a failure.** Reporting it put 318 lines in the
  log during B-02's 1 100-connection flood; Redis logs it at verbose level only.
* **Out of descriptors is survivable.** An `accept` that fails (EMFILE under a low `ulimit -n`) is
  logged and retried after 100 ms, as Redis keeps listening; it used to end the accept loop.
* **The selector is O(descriptors) per wake-up**, one thread for all connections. Irrelevant at the
  reference load; the reason not to promise hundreds of busy connections without a measurement.
