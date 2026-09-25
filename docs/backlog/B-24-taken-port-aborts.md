---
id: B-24
title: "A taken port aborts the server with a core dump instead of exiting cleanly"
status: wip
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

Research: [research-architecture](../research/research-architecture.md).
