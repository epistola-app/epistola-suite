---
type: feat
scopes: [demo]
audience: user
title: Demo users land in their own tenant, and share the demo one.
---

With demo mode on, a login now lands the visitor in their own tenant rather than the tenant picker — a customer works across several tenants and should choose, but a demo visitor has one that is theirs, so choosing is a click between them and the product. It is a post-login decision rather than a redirect on `/`, so a deep link someone was bounced off still wins, and the tenant list — which _is_ `/`, and is where "Switch tenant" points — stays reachable. Every demo user is also granted read/write on the shared `demo` tenant, where the showcase content lives (the quality showcase, the seeded findings, the banner) — but **not** `TENANT_ADMINISTRATOR`, because a shared tenant any visitor could reconfigure or restore over is a demo that breaks for everyone else. Their own tenant is where they are an administrator.
