---
type: feat
scopes: [build, security]
audience: dev
title: Demo mode ships as a separate application and image.
---

Demo mode gives every person who logs in a tenant of their own, and can carry a shared secret that authenticates every `/api` endpoint against every tenant — so a profile flag is a weak boundary for it: anyone who can edit a deployment's environment is one variable away. It is now its own application, `apps/epistola-demo`, which depends on `apps/epistola` and adds the demo classes, the `demo` profile's configuration and the bundled demo catalog. It publishes **`epistola-suite:{version}-demo`**; `epistola-suite:{version}` contains none of it, so no configuration can turn a production install into a demo — setting `SPRING_PROFILES_ACTIVE=demo` there fails the boot rather than quietly doing nothing. Because the artifact is the boundary, `demo` and `prod` stay freely combinable and a public demo keeps its production hardening.
