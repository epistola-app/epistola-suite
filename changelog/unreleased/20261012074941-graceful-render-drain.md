---
type: fix
scopes: [generation]
issues: [1065]
title: A stopping instance finishes its renders instead of abandoning them.
---

When an instance shut down — a rolling update, a scale-in, a node drain — it kept claiming new documents while the web server stopped, gave the renders already running only five seconds, and could mark a render it interrupted as failed instead of returning it to the queue. It now stops claiming the moment shutdown begins, lets running renders finish for up to `epistola.generation.polling.shutdown-drain-timeout` (default 20 seconds), and returns anything still unfinished to the queue for another instance. The Helm chart now gives pods a 60-second termination grace period to make room for it.
