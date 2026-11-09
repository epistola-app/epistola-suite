---
type: feat
scopes: [support]
title: Each support connection has its own privacy settings.
---

Whether tenants are named or confidential to a connection, and how much telemetry carries over it, is now
set per connection. Epistola support keeps the installation's settings. A partner connection follows them
until its configuration (`tenant-identity`, `telemetry`; Helm `tenantIdentity`, `telemetry`) or an
administrator under Platform → Epistola support sets its own, so a partner that hosts the installation
can see tenants by name while Epistola sees them as references.
