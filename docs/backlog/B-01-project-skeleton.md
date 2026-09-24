---
id: B-01
title: "Project skeleton: the modules, sborka, kore, a linuxX64 binary that answers PING"
status: done
priority: P0
size: S
stage: stage-1-protocol
epic: feature-resp-connection
---

# B-01 — Project skeleton: the modules, sborka, kore, a linuxX64 binary that answers PING

**Feature:** [feature-resp-connection](../features/feature-resp-connection.md).

Nothing exists yet. Every other item needs a build that produces the native binary the brief
promises (D-2) on the compiler the portfolio pins (D-7), and a place for each module of the brief's
§7 so that later items add code instead of arguing about layout.

- **The decision and its reason.** Gradle modules `resp`, `store`, `snapshot`, `server`,
  `conformance`, `bench` and a `deploy/` directory, applied through sborka's conventions. `server`
  starts through kore's `runUntilSignal` from day one, because retrofitting ordered shutdown into a
  server that already has state is the expensive order.
- **The store thread is in place from the first commit** (research D-14): even `PING` goes through
  the hand-off from the connection to the single store executor, so that the ordering and atomicity
  guarantees are structural, not something B-05 adds later.
- Rejected: a single-module start "to be split later" — the split is the part nobody does.
- Not covered: RESP parsing beyond what `PING` needs (B-02), anything in `store` (B-05).

- AC: `redis-cli -p 6379 ping` answers `PONG` from the `linuxX64` binary, built on the build machine through `wsl-run`.
- AC: `./gradlew buildEnvironment` shows Kotlin 2.4.20, and `ktor-network` resolves to 3.6.0; kesh's own `gradle/libs.versions.toml` has no `kotlin` line. *(Amended in B-01: it has no `ktor` line either — the shared catalog already carries 3.6.0, research §1.4 correction.)*
- AC: `SIGTERM` stops the binary through kore's plan with exit code 0.

## Findings

### Iteration 1 — 2026-09-24, done

Acceptance, on the Linux build machine (release binary `kesh.kexe`, 1 837 472 bytes, glibc only):

- `redis-cli` 7.2.16 (`docker run --network host redis:7.2`) → `PONG`; `PING hello` → `hello`;
  `GET k` → `ERR unknown command 'get', with args beginning with: 'k' `.
- `buildEnvironment`: Kotlin Gradle plugin 2.4.20. `dependencyInsight`: `ktor-network-linuxx64:3.6.0`,
  `kore-core:0.1.4`.
- `SIGTERM` ten times in a row, each with a client connected: exit 0, kore's transcript
  `SIGNAL … DRAIN … EXIT COMPLETED`, no uncaught exception, each restart binding over 3–30
  connections in TIME-WAIT.
- 35 tests (resp: 11 on the JVM and 11 on linuxX64; server: 13 on linuxX64), `ktlintCheck` clean.
  Mutations caught: `PING` answering `OK`; the drain not closing the listener; a batch answered in
  reverse; a bulk string complete without its CRLF; `SO_REUSEADDR` removed.

Found on the way, fixed here because the acceptance could not pass without them:

- **The drain crashed the process in 4 of 8 runs.** Closing the listener before cancelling the
  accept loop let `IOException: Accept failed` escape before the socket reported itself closed;
  an uncaught coroutine exception ends a Kotlin/Native process. Cancel first, close second: 0 of 20.
- **A restart after a stop with clients connected died with `EADDRINUSE`.** ktor binds without
  `SO_REUSEADDR`; Redis sets it. Now set, with a deterministic test.

Deviations from the item as written, recorded rather than silent:

- **Modules: `resp` and `server` only.** `store`, `snapshot`, `conformance`, `bench` and `deploy/`
  arrive with the items that give them code (B-05, B-14, B-04, B-03, B-16): an empty module has no
  build to verify, and `conformance` (JVM) and `bench` (partly shell) are shaped by their items.
- **No ktor pin in kesh.** research §1.4's correction: the shared catalog already had 3.6.0.
- **Inline commands are refused** with kesh's own wording until B-02, which owns them.

Left for later items: a stop always waits kore's 5 s `preDrainWait` because there is no readiness
gate yet (B-15, B-16); in-flight commands are cancelled rather than finished on stop (B-16).

## Code anchors

| Module | Path |
|---|---|
| build | `settings.gradle.kts` |
| build | `gradle/libs.versions.toml` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/Main.kt` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/KeshServer.kt` |
| resp | `resp/src/commonMain/kotlin/io/github/youndie/kesh/resp/CommandReader.kt` |

Research: [research-architecture](../research/research-architecture.md).
