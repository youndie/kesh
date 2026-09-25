---
id: B-30
title: "The allocator page size as a build property for the server"
status: wip
priority: P3
size: S
stage: stage-6-capacity
epic: feature-operations
---

# B-30 — The allocator page size as a build property for the server

Issue [#3](https://github.com/youndie/kesh/issues/3). D-19 builds the server with 256 KiB allocator
pages (`server/build.gradle.kts`, `allocatorPageSize = 256`) over sborka's 16, on the heap probe's
measurement (B-19 §1), not the server's. A comparison needs a server built with the other size, and
today that means editing the build file — easy to forget to revert, hard to name in a report.

- **The decision and its reason.** A Gradle property, as `-Pkesh.runtimeLogs=true` is:
  `-Pkesh.allocatorPageSize=<KiB>`, 256 by default. The one value sets the compiler's
  `fixedBlockPageSize` and a generated constant, so the binary can report the size it was built with
  — at startup and on `/metrics` — and a measured process proves which arm it is, not only its md5.
- **Deviation from the issue: not in `INFO`.** kesh's `INFO` prints only fields Redis 7.2 prints, in
  its sections (the `[fields]` check of `conformance/scripts/server/info.redis`); a kesh-only field
  would break that rule. `/metrics` is where the measurement scripts already read the process from.
- **Deviation: "byte-identical to today's" cannot hold** — the reporting is new code in the binary.
  What holds instead: the default build is byte-identical to `-Pkesh.allocatorPageSize=256`, and both
  pass the same `fixedBlockPageSize=256` as before.

- AC: `-Pkesh.allocatorPageSize=16` gives a binary with another md5, whose startup line and `/metrics`
  say 16; the default says 256.
- AC: the default build and `-Pkesh.allocatorPageSize=256` are byte-identical.
- AC: research D-19 names the property as the way to build the comparison arm.

## Code anchors

| Module | Path |
|---|---|
| server | `server/build.gradle.kts` |
| research | `docs/research/research-architecture.md` — D-19 |
