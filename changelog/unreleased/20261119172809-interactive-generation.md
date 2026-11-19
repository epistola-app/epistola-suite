---
type: feat
scopes: [generation, api]
title: A generation request can ask a person to complete its data in a form first.
---

Alpha, behind the `interactive-generation` feature toggle (off by default), implementing contract
2.1.0's interactive generation (contract ADR 0011). A caller sends `interactive` with
`generateDocument`. When data is missing, or every time with `trigger: always`, the job waits in
`awaiting-input` and the response carries a link to a form. A link opens for minutes and once; the
job status hands out a fresh one on every call (contract 2.1.1). The form is Suite's first page reachable without
signing in: it shows the fields the template's data contract asks for, pre-filled with what the
caller sent, renders a preview, and validates on the server before the job renders. Cancelled jobs say why
(`cancelReason`), and a job with a form gets a `cancelled` line on the collect stream.

Operators: the feature needs `epistola.generation.interactive.public-base-url`, the address people
reach this installation at. `default-expiry` (24h) and `max-expiry` (7d, at most 30 days) bound how
long a job waits for the person, `link-lifetime` (15m) how long a link opens, and
`session-idle-timeout` (60m) how long the person's session on the form lasts idle.
`preview-concurrency` (4) bounds the previews a node renders at once, over all forms. The boot is refused when partition retention is too short to keep a waiting
job that long. Return addresses are allowed per application, or through `allowed-origins` for
callers without one. The migration is not rollback-compatible: an installation that has used the
feature cannot go back to an earlier release.
