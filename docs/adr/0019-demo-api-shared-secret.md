# ADR 0019: Demo-profile shared secret for the REST API

- **Status:** Accepted
- **Date:** 2026-09-03
- **Deciders:** Epistola team
- **Tags:** security, auth, api, demo

## Context

The demo/training facility on epistola.app calls the Epistola REST API on behalf of whichever
visitor is using it. Since [the per-user tenant change](../auth.md#a-tenant-per-user), those
visitors get a tenant each, created at the moment they log in. The website therefore has to call an
API for a tenant that did not exist a second ago and has no credential of its own.

The existing authentication mechanisms do not cover this:

- **API keys** are per-tenant by construction (`api_keys.tenant_key`, and the filter sets
  `currentTenantId` from the row). A key would have to be minted for every visitor's tenant at
  login, stored somewhere the website can reach, and revoked with the tenant.
- **OAuth2 JWT** would mean the website holding a token per visitor, which is a second, parallel
  identity system for a demo.
- **The well-known `epk_demo_…` key** seeded by `DemoLoader` is scoped to the shared `demo` tenant
  and so cannot reach any visitor's tenant.

What the demo actually needs is not least privilege. It is one credential, held by one trusted
caller, that works everywhere — deliberately the opposite of the model the product enforces.

## Considered options

**A. Mint a per-tenant API key at login and hand it to the website.** Faithful to the existing
model, and the website's credential is scoped. But it needs a distribution channel from Epistola to
the website, a store keyed by visitor, and a revocation path; and each key is exactly as powerful as
the shared secret within the tenant that matters, so the added machinery buys little for a demo.

**B. A shared secret that authenticates every endpoint as an all-tenant superuser.** One
environment variable, no distribution, no lifecycle. It is a total bypass of the tenant and
permission model, which is acceptable only because it cannot exist outside a demo deployment.

**C. Extend the API-key model with a "global" key (`tenant_key IS NULL`).** Puts an unlimited
credential in the product's own data model and UI, where a self-hosted operator could create one.
The blast radius of a mistake is the whole product, not the demo.

## Decision

**Option B.** A single `epistola.demo.shared-secret`, supplied only from the environment
(`EPISTOLA_DEMO_SHAREDSECRET`), presented on the existing `Authorization: ApiKey <secret>` scheme so
callers need no new code path. It authenticates as a principal holding every `TenantRole` as a
_global_ role plus every `PlatformRole`, which is what lets it reach tenants it has no membership of
— including ones that do not exist yet.

Because the credential is unlimited, the containment is the design:

1. **Profile-gated, not property-gated.** `DemoSecurityConfiguration` is `@Profile("demo")`. This is
   the only `@Profile("demo")` in the repo — everything else in the demo package gates on
   `epistola.demo.enabled`, which the `local` profile also sets. A credential of this power must not
   appear on a developer's laptop merely because demo data is loaded.
2. **A configured secret outside that profile fails the boot.** `DemoSharedSecretSafetyValidator`
   runs unconditionally and throws. Silently ignoring the variable is the worse failure: an operator
   who set it in production would believe it was doing nothing _because they had configured it_.
3. **Minimum 32 characters**, enforced at wiring time. It is a bearer credential with no rate limit
   in front of it, so a guessable one is not a lesser version of the feature — it is a public API.
4. **Optional.** With no secret set, the demo profile starts exactly as before and the feature does
   not exist.

The filter does not authenticate the request itself. `ApiKeyAuthenticationFilter` answers 401 for
any `ApiKey` credential not starting with `epk_` _without continuing the chain_, so the demo filter
validates the secret and publishes the principal on `ApiKeyAuthenticationFilter.REQUEST_ATTR_PRINCIPAL`
— the "already validated for this request" hand-off that filter reads first, and the same attribute
its async re-dispatch path already relies on. Anything that is not the secret is left untouched.

It lives in `app.epistola.suite.demo` rather than `modules/rest-api`, so the shipped REST module
carries no notion of a bypass credential.

## Consequences

- A demo deployment holds one credential whose compromise exposes every demo tenant. That is
  accepted: demo tenants hold demo data, and the banner already says the data may be reset.
- The secret is **not** bound to a tenant (`currentTenantId` is null), so `/api/mcp` — whose tools
  take their tenant from the credential — and the partition block of `POST /api/ping` cannot be used
  with it. A tenant-selection header would lift this and was deliberately not added: it is one more
  thing to get wrong for a capability the demo website does not need.
- `ClientIdentityFilter` still requires `X-EP-Node-Id` on `/api/ping` and the collect endpoint, for
  this caller as for any other. "Authenticates everything" is about authorization, not about
  contract-level request validation.
- Writes are attributed to a `Demo Shared Secret` service account, so the audit trail says a machine
  did it, not a person. It cannot say _which_ visitor, which is the price of one shared credential.
- The `test` profile replaces the API chain with a permit-all one and the real chains are
  `@Profile("!test")`, so the wiring cannot be exercised by booting normally. `DemoSharedSecretEndToEndIT`
  re-declares the production chain and drives it over HTTP — the 401 short-circuit above and the
  filter ordering both pass in isolation and fail in a chain, so a filter-level test would not have
  caught either.

## References

- [`docs/auth.md`](../auth.md#demo-shared-secret) — usage and guards.
- [ADR 0015](0015-iframe-embedding-bridge.md) — the precedent for a demo-profile-only security
  relaxation, and the training facility this serves.
- `apps/epistola/src/main/kotlin/app/epistola/suite/demo/` — the whole feature.
