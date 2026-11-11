---
type: fix
scopes: [security, support]
title: An attached server log goes to each support connection as that connection's privacy allows.
---

The server log attached to a support request was pseudonymised once, when it was submitted, by the
installation's default. Every connection that handled the request, was attached to it or was
escalated to was then given that same text, so a connection set to pseudonymised telemetry still
received the log's values when the default was full. The request now keeps the lines as recorded,
and each connection is given them as its own telemetry setting allows. The installation's own pages
show them as the configured default does, as before.
