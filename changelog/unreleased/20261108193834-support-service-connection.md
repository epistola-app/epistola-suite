---
type: refactor
scopes: [support]
breaking: true
title: The support connection replaces the gRPC support hub.
---

An installation connects to the Epistola support service over HTTPS with OAuth client credentials. An installation administrator registers it under Platform → Support connections, after checking the company name and e-mail address it sends; nothing leaves the installation before that unless `epistola.support.auto-register` allows it. The services Epistola can grant are `telemetry`, `requests`, `mirror` and `usage`, and each is used only when an administrator selected it and Epistola granted it. When the support service is down or slow, nothing in the suite waits on it for more than a few seconds. **Action on upgrade:** remove `epistola.support.hub.*`, `.telemetry.*`, `.health-check.*` and `.entitlements.*` (Helm refuses `support.hub`), and register again. See `docs/support.md`.
