---
type: feat
scopes: [demo]
audience: user
title: A demo sandbox per person, not per company.
---

The demo profile derived a tenant from the email _domain_, so everyone at one company landed in the same tenant and overwrote each other's work. A demo is a place to try things, so the unit is now the person: the tenant key is the address's local part followed by six hex characters of `sha256` over it — `sander@degroot.dev` becomes `sander-665cdb` — and the tenant arrives seeded with the bundled demo catalog and `staging` and `production` environments. Users get every tenant role on their own tenant and, deliberately, no global or platform roles, so they cannot see another person's tenant or create further ones; roles the identity provider grants still win. This applies to the OIDC path only — form-login users keep taking their tenant from `epistola.auth.local-users`. Existing demo installs keep their old domain tenant, but people land in a fresh personal one at their next login.
