---
id: B-20
title: "Define \"the managed heap cannot serve the reference dataset\" before B-19 reports"
status: done
priority: P0
size: XS
stage: stage-1-protocol
epic: feature-memory-limit
---

# B-20 — Define "the managed heap cannot serve the reference dataset" before B-19 reports

**Feature:** [feature-memory-limit](../features/feature-memory-limit.md).

The brief says D-3 is revisited "only with a measurement that shows the managed heap cannot serve the
reference dataset". B-19 produces the measurement; nothing yet says what number means "cannot".
A threshold chosen after the numbers are seen is chosen to fit them.

- **The question for the owner.** Which of these, with which figures: the §5a heap does not fit on
  the reference host; GC pause p99 (or max) over a stated budget — for example a client timeout; the
  heap-to-user-data ratio over a stated factor. Research proposes none of them deliberately.
- Not a performance gate (D-9 stands): it decides whether the architecture holds, not whether a
  release is fast enough.

- AC: The threshold is written into research D-3, dated, before B-19's pull request is opened.

## Code anchors

| Module | Path |
|---|---|
| docs | `docs/research/research-architecture.md` |

Research: [research-architecture](../research/research-architecture.md).

## Answer — 2026-09-24, the owner

**Stop-the-world pause p99 at most 10 ms.** Recorded in research D-3 with its precise reading —
every collector pause in the churn window counts, at full scale, one subject on the host, the better
of the two encodings decides — before B-19 has measured anything.

**Superseded the same day, after B-19's first rows** (packed: 35–37 ms p99 at 1/8, 60–71 ms at 1/4):
the owner relaxed the threshold into a known limitation, on the condition that its mechanism is
understood. Research D-3 has the wording; B-19 now owes the explanation instead of a pass or fail.
