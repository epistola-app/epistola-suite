---
type: feat
scopes: [support]
title: An installation can connect to partners besides Epistola support.
---

A partner that hosts the suite or supports a client who does can be added under Platform → Support connections, by looking up its domain or entering its endpoint, with a token the partner issued, or declared in `epistola.support.connections`. Each connection has its own services, privacy settings and tenant consent. Support requests and usage go to a partner as to Epistola support, a partner can escalate a request to the connection it escalates to, and this works without a connection to Epistola. What a partner implements is published as `docs/support-partner-api.yaml` and `docs/support-partners.md`.
