---
type: fix
scopes: [demo, helm]
title: The demo image's migration Job migrates and exits instead of failing.
---

Started with `EPISTOLA_MIGRATION_MODE=migrate`, as the Helm chart's migration Job starts it, the `-demo` image migrated the database and then went on to start the whole application, which stopped for want of an authentication profile, so the Job failed and the install with it. It now migrates in an isolated context and exits, as the default image does.
