---
id: B-10
title: "SCAN, HSCAN, SSCAN, ZSCAN with the completeness guarantee"
status: done
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
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/commands/ScanCommands.kt` |
| conformance | `conformance/scripts/scan/` |

## Findings (2026-09-25)

- **`Keyspace.scan` is `dictScan`**: the reverse-binary cursor, both tables walked during a resize.
  The hash, set and sorted-set tables are `Keyspace`s, so `HSCAN`, `SSCAN` and `ZSCAN` share it;
  packed collections answer whole with cursor 0.
- **Acceptance 1.** `ScanScaleTest` (linuxX64): a million keys scanned in steps of about a hundred
  entries while 150 000 are added and 50 000 deleted in between, across a resize — every stable key
  seen. `ScanTest` does the same at 100 000 on both targets.
- **Acceptance 2.** A cursor that counts upwards instead of in reversed bits fails `ScanTest`'s
  completeness test. A cursor with half the reversal removed never returns to 0: that mutant first
  **hung** the suite, so the tests now fail an iteration that runs too long — a hang is not a
  verdict.
- **Oracle.** `[cursor]` iterates each server and compares the unions: 16 scripts, 995 comparisons,
  all agree with Redis 7.2.16 — 26 of them full iterations, among them 600-field hashes, 300-member
  and 300-integer sets and a 206-member sorted set. A mutant dropping one sampled entry per walk
  fails every `SCAN` iteration line.
- **Two things the oracle corrected**, both since read in the source: an unknown `SCAN … TYPE` name
  is not an error in 7.2 (the draft said it was), and `ZSCAN` prints a skiplist's scores as
  `%.17Lg`, which needed the double's exact decimal expansion (`RedisFloat.formatLongDoubleAuto`).

Research: [research-architecture](../research/research-architecture.md).
