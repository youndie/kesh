---
id: B-20
title: "Define \"the managed heap cannot serve the reference dataset\" before B-19 reports"
status: question
priority: P0
size: XS
stage: stage-1-protocol
---

# B-20 — Define "the managed heap cannot serve the reference dataset" before B-19 reports

**Feature:** `feature-memory-limit` — drafted in the *docs/layer-drafts* branch; the `epic` field is added when that document reaches `main`.

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
