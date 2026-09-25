---
id: B-22
title: "Choose the reference host for the load report and the soak"
status: done
priority: P1
size: XS
stage: stage-6-capacity
epic: feature-operations
---

# B-22 — Choose the reference host for the load report and the soak

**Feature:** [feature-operations](../features/feature-operations.md).

The brief's own floor is "at least an 8 GB host". The portfolio's measurement hosts have 7.7 GB, and
the one 16 GB machine is the build machine, shared and not idle (research §1.9). A pause measured
next to a second resident process is not a measurement of kesh (research §1.2).

- **The question for the owner.** Which host, with how many vCPUs and how much memory, and where
  the load generator runs.

- AC: The host is named in research Q-3 with its size, and B-17 is unblocked.

## Code anchors

| Module | Path |
|---|---|
| docs | `docs/research/research-architecture.md` |

## Decision (owner, 2026-09-25)

**The build machine, in a quiet window** (research Q-3): 20 cores and 16 GB in a WSL2 virtual
machine. It is not a reference host and the reports say so; a run counts only when neither the
Linux nor the Windows side has other work, checked before and after and committed with the run.
The load generator runs on another machine, which also keeps latency off the build machine's slow
monotonic clock. B-17 is unblocked.

Research: [research-architecture](../research/research-architecture.md).
