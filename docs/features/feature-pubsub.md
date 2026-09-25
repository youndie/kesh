---
id: feature-pubsub
title: Pub/Sub
type: feature
status: active
owner: unassigned
involved_services: [server, store, conformance]
client_entries: []
api: []
tags: [pubsub]
---

# Pub/Sub

## 1. Overview

A client publishes a message to a channel; every client subscribed to that channel, or to a pattern
matching it, receives it. Messages are not stored: a subscriber that is not connected when a
message is published never sees it. Out of the brief's scope (§2), added to v1 by the owner on
2026-09-25 (research Q-1, D-26), because the portfolio's one Redis client — kompot's multi-instance
realtime bus — uses nothing else. Built in B-27, on kesh's own transport (research D-31).

## 2. Business rules

* `SUBSCRIBE`, `PSUBSCRIBE` and their `UN` forms put a connection in RESP2 **subscribe mode**; while
  it has any subscription, only those commands, `PING`, `QUIT` and `RESET` are served, and anything
  else is refused with Redis's error (`redis/redis@7.2!/src/server.c`, `processCommand`).
* `PUBLISH` answers the number of receipts: a client subscribed to a channel and to a matching
  pattern counts, and receives the message, twice (`redis/redis@7.2!/src/pubsub.c`).
* A pattern is a glob, matched as `KEYS` and `SCAN MATCH` match (`store/.../Glob.kt`).
* A message goes to the channel's subscribers first, in the order they subscribed, then to each
  matching pattern's. Redis walks its patterns in its dictionary's order and kesh in its own, so
  which pattern's subscribers come first is no promise either server makes.
* A mass `UNSUBSCRIBE` or `PUNSUBSCRIBE` answers in no defined order either — and each confirmation
  carries the count left after it, so the counts follow that order.
* A subscriber receives messages in the order they were published — the registry is on the store
  thread, as the client registry is (research D-14).
* A subscriber that stops reading is disconnected once its pending output passes Redis 7.2's
  pub/sub limit: 32 MB at once, or 8 MB for 60 s (`clientBufferLimitsDefaults`,
  `redis/redis@7.2!/src/config.c`), counted by the bytes kesh has queued for it — nothing pushes back
  on a queue (research D-29). It never grows the heap without bound.
* `PING` from a subscribed client answers `*2 pong <message or "">`; `CLIENT LIST` shows `flags=P`,
  `sub=` and `psub=`; `INFO stats` has `pubsub_channels` and `pubsub_patterns`.

## 3. The commands this feature adds

| Command | Reply |
|---|---|
| `PUBLISH channel message` | the number of receipts |
| `SUBSCRIBE channel [channel …]` | one `subscribe` message per channel, with the count of the client's subscriptions |
| `UNSUBSCRIBE [channel …]` | one `unsubscribe` message per channel; none named means all |
| `PSUBSCRIBE pattern [pattern …]` | one `psubscribe` message per pattern |
| `PUNSUBSCRIBE [pattern …]` | one `punsubscribe` message per pattern; none named means all |

The replies' exact bytes are the oracle's (`conformance`), not this table's.

## 4. Code anchors

| Module | Path |
|---|---|
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/pubsub/PubSub.kt` — the registry, confirmations, delivery |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/net/RespConnection.kt` — `deliver`, and the output limit |
| server | `server/src/nativeMain/kotlin/io/github/youndie/kesh/server/command/CommandDispatcher.kt` — the commands and the subscribe mode's refusal |
| store | `store/src/commonMain/kotlin/io/github/youndie/kesh/store/Glob.kt` |
| conformance | `conformance/scripts/pubsub/pubsub.redis` — every command and delivery against Redis 7.2, over several connections |
| conformance | `conformance/src/jvmMain/kotlin/io/github/youndie/kesh/conformance/Main.kt` — kompot's bus across two instances |

## 5. Scenarios

### Scenario: A published message reaches a pattern subscriber
* **Given:** client A has sent `PSUBSCRIBE app:*`
* **When:** client B sends `PUBLISH app:home:42 hello`
* **Then:** B gets `:1`, and A gets a `pmessage` with the pattern `app:*`, the channel `app:home:42` and `hello`
* **Automated:** `conformance/scripts/pubsub/pubsub.redis`

### Scenario: A subscriber cannot run ordinary commands
* **Given:** a client that has sent `SUBSCRIBE news`
* **When:** it sends `GET k`
* **Then:** it gets Redis 7.2's subscribe-context error, and its subscription stays
* **Automated:** `conformance/scripts/pubsub/pubsub.redis`

### Scenario: kompot's bus runs on kesh
* **Given:** two instances of kompot's `RedisKompotUpdateBus` pointed at one kesh, through Lettuce
* **When:** one instance publishes on a topic
* **Then:** the other receives the message on that topic
* **Automated:** `conformance/src/jvmMain/kotlin/io/github/youndie/kesh/conformance/Main.kt` — `kompotBus`, every run of `conformance/run.sh` (kompot 0.37.0, Lettuce 7.6.0)

### Scenario: A subscriber that stops reading is dropped
* **Given:** a subscriber that never reads, and a publisher sending 1 KB messages to its channel
* **When:** the subscriber's pending output passes 32 MB
* **Then:** its connection is closed, and it leaves the registry (`pubsub_channels:0`)
* **Automated:** `server/src/nativeTest/kotlin/io/github/youndie/kesh/server/PubSubTest.kt::a subscriber that stops reading is dropped past the output limit and leaves the registry`

## 6. Out of scope

* `PUBSUB CHANNELS|NUMSUB|NUMPAT`, sharded `SSUBSCRIBE`/`SPUBLISH`, RESP3 push messages, keyspace
  notifications — new items if a consumer needs them.

## 7. Quirks

* **`used_memory` does not count what a subscriber has not been sent** — kesh's accounting is the
  dataset's (research D-10); Redis's `used_memory` includes client output buffers. The limit is on
  the queue itself, so it holds either way.
* **`RESET`, `SSUBSCRIBE` and `SPUBLISH` are unknown commands in kesh**; Redis allows `RESET` in
  subscribe mode, and the refusal still names it, word for word.
