---
id: B-24
title: "A taken port aborts the server with a core dump instead of exiting cleanly"
status: done
priority: P2
size: XS
stage: stage-5-operations
---

# B-24 — A taken port aborts the server with a core dump instead of exiting cleanly

**Feature:** `feature-operations` — drafted in the *docs/layer-drafts* branch; the `epic` field is added when that document reaches `main`.

Found in B-13 (2026-09-25): a second kesh started on a port another holds dies with
`Uncaught Kotlin exception: …AddressAlreadyInUseException: EADDRINUSE (98)`, a stack trace, and exit
code 134 (`SIGABRT`, a core dump) — from `KeshServer.start`'s `bind`. Redis logs one line
(`Could not create server TCP listening socket *:6379: bind: Address already in use`) and exits 1.
Under an orchestrator the difference is a crash-loop with dumps against an ordinary exit.

- **The decision and its reason.** Catch the bind failure at startup, log it in one line naming the
  address, exit with a non-zero status through kore's ordered stop — no uncaught exception.
- AC: Starting kesh on a taken port prints one line naming the address and exits with status 1,
  without a core dump; a test drives it.

## Code anchors

| Module | Path |
|---|---|
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/KeshServer.kt` |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/Main.kt` |

## Findings (2026-09-25)

- **Done from `main`**: found in B-13 and filed on its branch, fixed here; the same `StartupFailure`
  B-14 uses for a damaged snapshot, so the two meet cleanly when B-14 merges.
- **Acceptance.** `KeshServerTest` starts a second server on a port the first listens on and requires
  `StartupFailure` naming the address; through the binaries, the second prints
  `kesh: could not listen on 0.0.0.0:16395: EADDRINUSE (98): Address already in use` and exits 1, no
  core dump, the first stops normally. A mutant throwing another exception fails the test.

Research: [research-architecture](../research/research-architecture.md).
