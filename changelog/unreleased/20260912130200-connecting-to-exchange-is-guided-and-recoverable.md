---
type: feat
scopes: [exchange]
audience: user
title: Connecting to Exchange is a guided, recoverable flow.
---

The Exchange settings page is built around the states a connection actually has. Setup follows secure browser redirects and discovers Exchange without a manually configured base URL. Exchange must be reached over HTTPS, and a discovered OAuth endpoint must be on the issuer's own origin. Credentials Exchange refuses lead to a guided recovery rather than a dead end, reconnecting recovers when Exchange no longer knows this installation, and a response Exchange rejects explains itself on the page instead of becoming an error page.
