---
id: B-32
title: "Publish the image, so a consumer outside this repository can deploy kesh"
status: done
priority: P1
size: S
stage: stage-5-operations
epic: feature-operations
---

# B-32 — Publish the image, so a consumer outside this repository can deploy kesh

B-16 built the image and sized the chart, and `deploy.md` said the rest: *not published to a registry
yet: the image is built where it runs*. That held while kesh's only runs were the bench stand and kind.
The first consumer outside the repository — a reference service whose realtime bus is kompot's
`kompot-realtime-redis` — deploys from a chart on a cluster, and a chart there needs an image it can pull.

- **The decision: GHCR, from a workflow on every push to `main` and on `v*` tags, the xyk pattern.**
  `sha-<commit>` names the commit and is what a deployment pins; `main` moves. The image is
  `deploy/Dockerfile` unchanged — the one B-16 sized the chart on and B-18 soaked — not a second,
  smaller variant nobody measured.
- **The published digest is run, not only pushed:** under `--memory 1g` (B-26's start-up check is in
  the path), and asked to deliver a message to a `PSUBSCRIBE` on another connection — the consumer's
  actual use, in two `redis-cli` calls. The step was tried against Redis 7.2 first, and its control
  (a publish to a channel outside the pattern) fails it.
- The chart's default image becomes `ghcr.io/youndie/kesh:main`.
- Not covered: publishing the chart. A consumer copies or vendors `deploy/chart/` until a second one
  asks for it.

- AC: a push to `main` leaves `ghcr.io/youndie/kesh:sha-<commit>` and `:main`, publicly pullable.
- AC: the workflow fails when the published image does not start or does not deliver the message.
- Anchors: `.github/workflows/publish.yaml`, `deploy/chart/values.yaml`, `docs/services/deploy.md`.

## Findings — 2026-10-06

- **Tried before merge** by pointing the trigger at this branch once (run 37435337068, then removed):
  `ghcr.io/youndie/kesh@sha256:78331561…` pushed, **public** without a settings change (the package
  inherited the repository's visibility), pullable anonymously; the published digest started under
  `--memory 1g` and delivered the `PSUBSCRIBE`d message.
