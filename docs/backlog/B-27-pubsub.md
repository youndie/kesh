---
id: B-27
title: "Pub/Sub: PUBLISH, SUBSCRIBE and PSUBSCRIBE in RESP2 subscribe mode"
status: open
priority: P1
size: M
stage: stage-1-protocol
blocked_by: [B-21]
---

# B-27 — Pub/Sub: PUBLISH, SUBSCRIBE and PSUBSCRIBE in RESP2 subscribe mode

**Feature:** `feature-pubsub` — drafted in the *docs/layer-drafts* branch; the `epic` field is added
when that document reaches `main`.

The brief left Pub/Sub out of v1; the owner added it on 2026-09-25 (B-21, research Q-1, D-26),
because the portfolio's only Redis client is kompot's multi-instance realtime bus
(`kompot-realtime-redis`, Lettuce 7.6.0), and it uses nothing else: `PUBLISH` on one connection and
one `PSUBSCRIBE "<prefix>:*"` on another (research §1.1). With this item that bus is kesh's first
real consumer.

- **The decision and its reason.** `PUBLISH`, `SUBSCRIBE`, `UNSUBSCRIBE`, `PSUBSCRIBE`,
  `PUNSUBSCRIBE`, and the RESP2 subscribe mode they put a connection in, byte for byte as Redis 7.2
  answers them — the conformance oracle decides, as for every command. The subscription registry
  lives on the store thread with the client registry (D-14), so a `PUBLISH` counts its receivers
  exactly and delivers in publish order without a lock; a pattern matches with the same glob as
  `KEYS` and `SCAN MATCH` (`store/.../Glob.kt`).
- **What a subscriber connection may still send** — the allowed commands and the error for the rest,
  `PING`'s different reply in the mode — is read from Redis's source (`redis/redis@7.2!/src/server.c`,
  `processCommand`; `redis/redis@7.2!/src/pubsub.c`), never retyped.
- **A slow subscriber must not grow memory without bound.** A message waits in the subscriber's
  outgoing buffer; Redis bounds that buffer (`client-output-buffer-limit pubsub 32mb 8mb 60`) and
  drops the client past it (`clientBufferLimitsDefaults`, `redis/redis@7.2!/src/config.c`). kesh does the same with those defaults — a test that stops reading on a
  subscriber and keeps publishing shows the connection dropped, not the heap grown. Rejected:
  blocking `PUBLISH` until the subscriber reads — one stuck client would stop the store thread.
- **Not covered:** `PUBSUB CHANNELS|NUMSUB|NUMPAT`, sharded `SSUBSCRIBE`/`SPUBLISH`, RESP3 push
  messages, keyspace notifications. Each is a new item if a consumer needs it.

- AC: Every Pub/Sub command, a subscriber's allowed and refused commands, and message delivery to channel and pattern subscribers agree with Redis 7.2 in the conformance harness, including a client subscribed to a channel and to a pattern that both match.
- AC: kompot's `RedisKompotUpdateBus`, pointed at kesh, delivers a published message from one bus instance to another — run against the real Lettuce client, not a double.
- AC: A subscriber that stops reading is disconnected once its buffer passes the limit, and `used_memory` and resident memory return to where they were.

## Code anchors

| Module | Path |
|---|---|
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/` |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/Glob.kt` |
| conformance | `conformance/scripts/` |

Research: [research-architecture](../research/research-architecture.md) §1.1, Q-1, D-26.
