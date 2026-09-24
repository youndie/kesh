---
id: B-22
title: "Choose the reference host for the load report and the soak"
status: question
priority: P1
size: XS
stage: stage-6-capacity
---

# B-22 — Choose the reference host for the load report and the soak

**Feature:** `feature-operations` — drafted in the *docs/layer-drafts* branch; the `epic` field is added when that document reaches `main`.

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

Research: [research-architecture](../research/research-architecture.md).
