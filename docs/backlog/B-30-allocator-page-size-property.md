---
id: B-30
title: "The allocator page size as a build property for the server"
status: done
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

## Findings — 2026-09-25: done

- **AC 1 — met.** On the build machine: the default release binary md5 `38cf4add…`, the
  `-Pkesh.allocatorPageSize=16` one `6a5afd3e…`; the second's startup line reads `kesh: built with 16
  KiB allocator pages` and its `/metrics` `kesh_build_info{allocator_page_size_kb="16"} 1`, the
  default's 256. That the compiler got the other page size and not only the other constant: the link's
  `--debug` log of the 16 build carries `fixedBlockPageSize=16`.
- **AC 2 — met.** The default build and `-Pkesh.allocatorPageSize=256`: `cmp` finds them identical.
- **AC 3 — met.** Research D-19 names the property, and its stale watch on 64 I/O threads is amended.
- `MetricsTest::the build info names the allocator page size the binary was built with`; a mutant
  dropping the line is caught by it. A first mutation made with `sed` matched nothing after the
  formatter and ran green on unchanged code — the empty `git diff` said so, and it was redone.
