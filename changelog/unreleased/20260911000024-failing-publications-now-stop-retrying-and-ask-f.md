---
type: fix
scopes: [exchange]
audience: user
title: Failing publications now stop retrying and ask for a decision.
---

After `epistola.exchange.max-attempts` consecutive transient failures a publication becomes a retryable `Failed` rather than retrying forever while holding its retained release archive. Work that is merely not ready yet — enrollment incomplete, or a tenant that paused the feature — is deferred on a slow cadence instead, and consumes no retry budget.
