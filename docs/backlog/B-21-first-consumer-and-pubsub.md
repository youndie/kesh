---
id: B-21
title: "Who is the first consumer, and does Pub/Sub belong in v1?"
status: done
priority: P0
size: XS
stage: stage-1-protocol
epic: feature-resp-connection
---

# B-21 — Who is the first consumer, and does Pub/Sub belong in v1?

**Feature:** [feature-resp-connection](../features/feature-resp-connection.md).

Research §1.1 searched every working tree in the portfolio: the only Redis consumer is kompot's
multi-instance realtime bus, and it uses only `PUBLISH` and `PSUBSCRIBE` — which v1 excludes. No
module uses Redis for sessions, counters, rate limits or leaderboards, which is what the brief's §1
and §5a are sized for.

- **The question for the owner**, with the options research sees: (a) keep the scope and name the
  future service §5a is for; (b) add Pub/Sub (`PUBLISH`, `SUBSCRIBE`, `PSUBSCRIBE` and their `UN`
  forms in RESP2 subscribe mode), making kompot's bus the first real consumer; (c) size v1 down to
  what a first consumer needs, which also shrinks research R-1.
- Nothing is blocked on it formally, but B-19's scale and B-05 onwards are cheaper to change before
  they start.

- AC: The answer is recorded in research Q-1, and the brief-derived feature documents in the drafts branch are amended to match.

## Code anchors

| Module | Path |
|---|---|
| docs | `docs/research/research-architecture.md` |

## Decision (owner, 2026-09-25)

**(b) — add Pub/Sub.** `PUBLISH`, `SUBSCRIBE`, `PSUBSCRIBE` and their `UN` forms in RESP2 subscribe
mode join v1, and kompot's multi-instance bus is kesh's first real consumer. Recorded in research
Q-1 and D-26; built by [B-27](B-27-pubsub.md). The capacity target was kept, not sized down.

Research: [research-architecture](../research/research-architecture.md).
