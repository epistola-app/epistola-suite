---
type: feat
scopes: [demo, security]
audience: dev
title: A shared secret that authenticates the whole REST API — demo profile only.
---

The demo website calls Epistola on behalf of whichever visitor is using it, and those visitors now get a tenant created at the moment they log in, so there is no per-tenant API key to mint and track ahead of time. `EPISTOLA_DEMO_SHAREDSECRET` supplies one credential that works everywhere, presented on the existing `Authorization: ApiKey <secret>` scheme so callers need no new code path. **It is a total bypass of the tenant and permission model** — the principal holds every tenant role as a _global_ role plus every platform role, so it passes for every tenant, including ones that do not exist yet. Three things confine it: the wiring is `@Profile("demo")` rather than the `epistola.demo.enabled` property (which `local` also sets), a secret configured in any other profile **fails the boot** instead of being silently ignored, and it must be at least 32 characters. With no secret configured the demo profile starts exactly as before and the feature does not exist. Not bound to a tenant, so `/api/mcp` and the partition block of `POST /api/ping` are deliberately not usable with it. See [ADR 0019](docs/adr/0019-demo-api-shared-secret.md) and [`docs/auth.md`](docs/auth.md#demo-shared-secret).
