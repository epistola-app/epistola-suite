---
type: feat
scopes: [support]
title: An installation can connect to a partner, signed in with a token the partner issued.
---

Besides Epistola support, `epistola.support.connections` (Helm `support.connections`, the token from a
Secret) declares further support connections, such as a partner that hosts the suite or supports a
client who hosts it. Each is checked regularly and listed under Platform → Support connections with what it
grants and what is used over it. This works without the connection to Epistola support. Sending support
requests and usage over a partner connection follows.
