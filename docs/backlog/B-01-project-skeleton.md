---
id: B-01
title: "Project skeleton: the modules, sborka, kore, a linuxX64 binary that answers PING"
status: open
priority: P0
size: S
stage: stage-1-protocol
---

# B-01 — Project skeleton: the modules, sborka, kore, a linuxX64 binary that answers PING

**Feature:** `feature-resp-connection` — drafted in the *docs/layer-drafts* branch; the `epic` field is added when that document reaches `main`.

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
- AC: `./gradlew buildEnvironment` shows Kotlin 2.4.20, and no `gradle/libs.versions.toml` in the repository shadows the shared catalog (research §1.8).
- AC: `SIGTERM` stops the binary through kore's plan with exit code 0.

## Code anchors

| Module | Path |
|---|---|
| build | `settings.gradle.kts` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/` |
| resp | `resp/` |
| store | `store/` |

Research: [research-architecture](../research/research-architecture.md).
