---
type: feat
scopes: [exchange]
audience: user
title: Catalog releases publish through a durable background outbox.
---

The local release transaction stores the exact portable ZIP and succeeds independently of Exchange availability. Catalog pages show attempt history and allow an unchanged current release to be published later, or a remote failure to be retried with a fresh idempotency key. Cluster-safe workers pause tenant work while its feature is off, retain archives until a terminal Exchange decision, and maintain expiring connection credentials independently.
