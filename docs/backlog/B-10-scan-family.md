---
id: B-10
title: "SCAN, HSCAN, SSCAN, ZSCAN with the completeness guarantee"
status: open
priority: P1
size: M
stage: stage-2-types
epic: feature-keyspace
blocked_by: [B-06, B-07, B-08, B-09]
---

# B-10 — SCAN, HSCAN, SSCAN, ZSCAN with the completeness guarantee

**Feature:** [feature-keyspace](../features/feature-keyspace.md).

`SCAN` promises that every key present for the whole iteration is returned at least once, across
resizes. Redis gets that from a reverse-binary cursor over a power-of-two table; kesh's table (B-05,
research D-12) is built to allow the same.

- **The decision and its reason.** The cursor is the reverse-binary bucket index, valid across an
  incremental rehash; `COUNT`, `MATCH` and `TYPE` as in Redis. Packed small collections return
  everything in one call with cursor 0, as Redis does for its small encodings.
- Conformance compares the union of a full iteration, not the cursor values (research D-5).

- AC: The 1 M-key scan scenario of `feature-keyspace` passes while keys are being added and deleted concurrently, including across a resize.
- AC: A mutation test that breaks the cursor's bit reversal makes the completeness test fail.

## Code anchors

| Module | Path |
|---|---|
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/keyspace/` |
| conformance | `conformance/scripts/scan/` |

Research: [research-architecture](../research/research-architecture.md).
