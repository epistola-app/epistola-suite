---
type: refactor
scopes: [support]
audience: user
breaking: true
title: The support connection replaces the gRPC support hub.
---

An installation now connects to the Epistola support service over HTTPS with OAuth client
credentials instead of the gRPC hub. An installation administrator registers it under Platform →
Epistola support, after checking the company name and e-mail address it sends; it sends its own
public key, and the page shows a claim code as a reference while Epistola contacts that address.
Nothing leaves the installation before that, unless `epistola.support.auto-register` (Helm
`support.autoRegister`) lets it register on its own. A claim code nobody activated within seven days
expires: the page says so and offers Register, or with auto-register the installation registers
again at once. Epistola may decline an installation (the page says so; Register again applies again)
or pause a service for a while, with a reason and until when.

The services Epistola can grant are `telemetry`, `requests`, `mirror` and `usage`. One is used only
when an installation administrator selected it on that page (or `epistola.support.services`, Helm
`support.services`, until one does; nothing by default) and Epistola granted it. This release sends
nothing under any of them yet: hub telemetry and the hub feedback sync are removed, feedback stays in
the installation, and each service arrives over the new connection in a later release.

An installation administrator can disconnect under Platform → Epistola support: the support service
is told, and the installation forgets its key and does not register again by itself, also with
auto-register. When the support service cannot be reached, nothing changes and the page offers to
remove the connection here only.

When the support service is down, slow or answers with errors, the suite carries on: no page, request
or scheduled job waits on it for more than a few seconds (`epistola.support.read-timeout` is a
deadline for a whole call). On an installation with several nodes, the connection check runs on one
node at a time, and a node whose clock is badly off says so on the support page instead of marking
the registration refused for the whole installation.

On upgrade, remove `epistola.support.hub.*`, `epistola.support.telemetry.*`,
`epistola.support.health-check.*` and `epistola.support.entitlements.*`: they are ignored (Helm
refuses `support.hub`). Set `epistola.support.endpoint` (Helm `support.endpoint`) only if you used a
self-hosted hub. An installation with `epistola.support.enabled=true` has to register again and needs
a new claim. See docs/support.md and docs/support-protocol.md.
