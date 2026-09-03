# Authentication

Epistola Suite uses **bean-driven authentication** that adapts to the runtime environment based on which Spring beans are present:

| Bean Present                   | Authentication Method                                     | Provided By                                                                                       |
| ------------------------------ | --------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| `UserDetailsService`           | Form-based login with configurable users                  | `LocalUserDetailsService` (`local` / `localauth` profiles)                                        |
| `ClientRegistrationRepository` | OAuth2/OIDC (Keycloak, authentik, any compliant provider) | Spring Security auto-config from `spring.security.oauth2.*` (the `keycloak` profile, or env vars) |
| Neither                        | **Startup failure** — safety validator blocks             | —                                                                                                 |

OIDC login is **provider-neutral**: it activates for any registration id, so the provider is a
deployment choice, not a code change. For **local development** the `keycloak` profile bundles a
ready-to-use registration against a local Keycloak (opt in with `local,keycloak`; it lives in
`application-local.yaml`). Every other environment — staging, production, and any provider such as
**authentik** — is configured purely through the standard `spring.security.oauth2.client.*`
properties / env vars, which the Helm chart emits. See [authentik-setup.md](authentik-setup.md).

## How It Works

Authentication methods are **not determined by profile name checks**. Instead:

1. **`LocalUserDetailsService`** is annotated `@Profile("local | localauth")` — it's the single source of truth for which profiles get form login.
2. **`SecurityConfig`** and **`LoginHandler`** check for `UserDetailsService` bean presence (not profile names).
3. **`OAuth2UserProvisioningService`** is provider-neutral and registered unconditionally; it is only invoked through `SecurityConfig`'s `oauth2Login`, which is wired solely when a `ClientRegistrationRepository` bean exists — so it stays inert unless OAuth2 is configured.

Adding a new form-login profile only requires updating `LocalUserDetailsService`'s `@Profile` annotation.

## Profile Composition

Profiles are orthogonal — each controls a single concern:

| Profile     | Concern                                                                                                                                                                      |
| ----------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `local`     | Dev experience: devtools, filesystem serving, editor watch. Implies form login + demo data.                                                                                  |
| `localauth` | Form login with configurable users (env-var overridable)                                                                                                                     |
| `keycloak`  | **Local-dev only** — adds an OAuth2/OIDC registration against a local Keycloak. Opt in with `local,keycloak`. Staging/production OIDC comes from env vars, not this profile. |
| `demo`      | Demo data, a personal tenant per signed-in user, and the optional API shared secret                                                                                          |
| `prod`      | Production hardening: flyway clean disabled, tuned concurrency                                                                                                               |

### Environment Matrix

Staging/production run in Kubernetes, where the Helm chart supplies the OIDC registration via
`SPRING_SECURITY_OAUTH2_*` env vars — so they do **not** use the `keycloak` profile.

| Environment      | Profiles         | OIDC source        | Auth         | Demo |
| ---------------- | ---------------- | ------------------ | ------------ | ---- |
| Local dev        | `local`          | —                  | Form login   | yes  |
| Local + Keycloak | `local,keycloak` | `keycloak` profile | Form + OAuth | yes  |
| Test/Staging     | `demo`           | Helm `oidc.*` env  | OAuth2 only  | yes  |
| Test/Staging     | `demo,localauth` | Helm `oidc.*` env  | Both         | yes  |
| Production       | `prod`           | Helm `oidc.*` env  | OAuth2 only  | no   |

## Safety Guards

### AuthenticationSafetyValidator

A `SmartInitializingSingleton` that runs at startup and fails fast if:

- **In-memory users in production**: `local` or `localauth` profile combined with `prod` → blocks startup.
- **No authentication configured**: Neither `UserDetailsService` nor `ClientRegistrationRepository` exists → blocks startup (all requests would 403).

Skipped in `test` profile (tests use permit-all security).

### UserDetailsServiceAutoConfiguration Excluded

Spring Boot's `UserDetailsServiceAutoConfiguration` is excluded to prevent it from creating a default `InMemoryUserDetailsManager` with a random password when no profile provides a `UserDetailsService`. This ensures the safety validator catches the "no auth" case.

## Local Development

Start the application with the `local` profile:

```bash
./gradlew :apps:epistola:bootRun --args='--spring.profiles.active=local'
```

Default test accounts (configured in `application-local.yaml`):

| Username      | Password | Description                             |
| ------------- | -------- | --------------------------------------- |
| `admin@local` | `admin`  | Admin user with access to all tenants   |
| `user@local`  | `user`   | Regular user with access to demo-tenant |

## Local Auth Profile

The `localauth` profile provides form-based login with **configurable** users, suitable for staging/test environments where you need form login alongside OIDC (OIDC comes from env vars):

```bash
SPRING_PROFILES_ACTIVE=demo,localauth
```

Override credentials via environment variables:

| Variable                        | Default       |
| ------------------------------- | ------------- |
| `LOCAL_AUTH_ADMIN_USERNAME`     | `admin@local` |
| `LOCAL_AUTH_ADMIN_PASSWORD`     | `admin`       |
| `LOCAL_AUTH_ADMIN_DISPLAY_NAME` | `Local Admin` |
| `LOCAL_AUTH_ADMIN_TENANT`       | `demo`        |
| `LOCAL_AUTH_USER_USERNAME`      | `user@local`  |
| `LOCAL_AUTH_USER_PASSWORD`      | `user`        |
| `LOCAL_AUTH_USER_DISPLAY_NAME`  | `Local User`  |
| `LOCAL_AUTH_USER_TENANT`        | `demo`        |

## Demo Profile

The `demo` profile loads demo data (tenant, themes, templates) and adds two demo-only behaviours on
top: a personal tenant per signed-in user, and an optional shared secret for the REST API. It does
not change which authentication mechanisms exist. In staging/production, OIDC is supplied by env
vars (Helm), so `demo` is combined with `prod` and/or `localauth` rather than a `keycloak` profile:

```bash
# OAuth2 (from env vars) + demo data
SPRING_PROFILES_ACTIVE=demo

# OAuth2 + form login + demo data
SPRING_PROFILES_ACTIVE=demo,localauth
```

### A tenant per user

When `epistola.demo.enabled=true` and an OIDC login carries **no** `/epistola/` groups and no flat
roles, `DemoLoginMembershipResolver` gives that person a tenant of their own. The key is their email
address's local part, then a short hash of the whole address:

| Email                | Tenant key          |                                       |
| -------------------- | ------------------- | ------------------------------------- |
| `sander@degroot.dev` | `sander-665cdb`     |                                       |
| `j.doe+test@acme.io` | `j-doe-test-6f7f03` |                                       |
| `j.doe.test@acme.io` | `j-doe-test-9db5b1` | same label, different address         |
| `admin@acme.io`      | `admin-94039b`      | reserved words are fine with a suffix |
| `1st@acme.io`        | `u-1st-a5752e`      | a key must start with a letter        |
| `日本@example.jp`    | `u-6196c7`          | nothing ASCII in the local part       |

The tenant is named after the address that created it, and a new one is seeded with the bundled demo
catalog plus `staging` and `production` environments.

**Every** key is hashed, not only the ones that would otherwise clash. That makes uniqueness a
property of the key rather than something to check for on each login, and it is why a reserved word,
a leading digit or a local part with no ASCII in it all just work — the label in front of the hash is
free to be whatever is readable, or nothing at all.

#### The key derivation, exactly

Deliberately reproducible without the application — no salt, no secret, no installation-specific
input. This is an identifier, not a credential: it exists so two addresses cannot land in one
tenant, and nothing is protected by its being hard to guess.

Given a raw address:

1. **Normalize.** Trim surrounding whitespace, lowercase. Everything below uses this form, including
   the hash input.
2. **Split on the first `@`.** The part before it is the _local part_; the part after it is the
   _domain_. If either is empty or whitespace, there is no key — stop.
3. **Slugify the local part** into a _label_: replace every run of characters outside `[a-z0-9]` with
   a single `-`, then strip any leading and trailing `-`.
4. **Make the label able to lead.** A `TenantKey` must start with a letter, and the label leads, so:
   an empty label becomes `u`; a label starting with a digit is prefixed with `u-`; otherwise it is
   used as is. Call the result the _stem_.
5. **Truncate the stem** to 56 characters (63 − 6 for the hash − 1 for the separator), then strip a
   trailing `-` again. This second strip matters: truncating can land on a hyphen, and `a--b` is not
   a valid key.
6. **Hash.** Take the first 6 hex characters of `sha256` over the normalized address, as UTF-8.
7. **Join** with a hyphen: `<stem>-<hash>`.

```bash
# step 6, on its own
printf %s "sander@degroot.dev" | sha256sum | cut -c1-6    # 665cdb   (shasum -a 256 on macOS)
```

A reference implementation of the whole thing:

```python
import hashlib, re

def tenant_key(raw_email: str) -> str | None:
    email = raw_email.strip().lower()
    local, sep, domain = email.partition("@")
    if not sep or not local.strip() or not domain.strip():
        return None
    label = re.sub(r"[^a-z0-9]+", "-", local).strip("-")
    stem = "u" if not label else (label if label[0].isalpha() else f"u-{label}")
    head = stem[:56].strip("-")
    return f"{head}-{hashlib.sha256(email.encode()).hexdigest()[:6]}"
```

Worked examples, one per branch:

| Email                | Label        | Stem         | Key                 |
| -------------------- | ------------ | ------------ | ------------------- |
| `sander@degroot.dev` | `sander`     | `sander`     | `sander-665cdb`     |
| `j.doe+test@acme.io` | `j-doe-test` | `j-doe-test` | `j-doe-test-6f7f03` |
| `admin@acme.io`      | `admin`      | `admin`      | `admin-94039b`      |
| `1st@acme.io`        | `1st`        | `u-1st`      | `u-1st-a5752e`      |
| `日本@example.jp`    | _(empty)_    | `u`          | `u-6196c7`          |
| `a@b@c.com`          | `a`          | `a`          | `a-a2f315`          |

Every step above is pinned by `DemoTenantKeyDerivationTest`, including the two that are easy to get
wrong — the second `-` strip in step 5, and the fallback prefix counting against the cap — so this
documentation cannot drift from the code without a test failing.

#### What they can do

| Tenant                | Roles                                                |
| --------------------- | ---------------------------------------------------- |
| Their own             | every `TenantRole`, including `TENANT_ADMINISTRATOR` |
| The shared `demo` one | every `TenantRole` **except** `TENANT_ADMINISTRATOR` |
| Globally / platform   | none                                                 |

They also get read/write on the shared `demo` tenant because that is where the showcase content
lives — the demo catalog's quality showcase, the seeded findings, the banner — so a visitor who only
ever saw their own sandbox would miss most of what the demo is for. Not administration, though:
`TENANT_ADMINISTRATOR` is what carries `TENANT_SETTINGS`, `TENANT_USERS`, `CATALOG_MANAGE` and
`TENANT_RESTORE`, and a shared tenant any visitor could reconfigure or restore over is a demo that
breaks for everyone else. Their own tenant is where they get to be an administrator.

No global and no platform roles is what stops a demo user seeing a _third_ person's tenant
(`ListTenants` filters on membership when there are no global roles and no `TENANT_MANAGER`) or
creating further tenants (`CreateTenant` requires `TENANT_MANAGER`). Roles the identity provider
grants take precedence: the resolver is only consulted when the token carried none, and platform
roles from the token survive it.

#### Landing straight in their own tenant

With demo mode on, `GET /` redirects a signed-in user to `/tenants/<their own>` instead of rendering
the tenant picker. Everywhere else the picker is right — a customer works across several tenants and
should choose. A demo visitor has exactly one that is theirs, so choosing is a click between them and
the product.

`DemoLandingRoutes` registers a `GET /` at `Ordered.HIGHEST_PRECEDENCE`, ahead of the one
`TenantRoutes` declares; `RouterFunctionMapping` sorts router beans and takes the first match. When
there is no personal tenant to open — a principal whose memberships came from the identity provider
rather than from this resolver — it delegates to the normal handler, so the picker is still there.

This applies to the **OIDC** path only. Form-login users (`local` / `localauth`) keep taking their
tenant from `epistola.auth.local-users`.

> Note that `local` also sets `epistola.demo.enabled=true`, so local development gets this behaviour
> too — via Keycloak, not via form login.

## Production (OAuth2/OIDC)

Production uses OAuth2/OIDC with any compliant provider (Keycloak, authentik, …). The registration
is supplied entirely through env vars — there is **no** `keycloak` profile in production. The Helm
chart emits these from its `oidc.*` values; to configure by hand, set (with `<REG>` = your
`oidc.registrationId`, e.g. `KEYCLOAK` or `AUTHENTIK`):

```bash
export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<REG>_CLIENTID=epistola-suite
export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<REG>_CLIENTSECRET=<your-secret>
export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<REG>_SCOPE=openid,profile,email
export SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_<REG>_ISSUERURI=https://sso.example.com/realms/epistola
export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI=https://sso.example.com/realms/epistola
```

Activate profiles (no `keycloak`):

```bash
SPRING_PROFILES_ACTIVE=prod
```

The presence of the registration properties enables OIDC login; the `prod` profile provides
production hardening (flyway clean disabled, tuned concurrency). See
[keycloak-setup.md](keycloak-setup.md) / [authentik-setup.md](authentik-setup.md).

### AuthProvider Derivation

The `AuthProvider` stored on `User` records is derived from the OAuth2 registration ID:

| Registration ID | AuthProvider   |
| --------------- | -------------- |
| `keycloak`      | `KEYCLOAK`     |
| anything else   | `GENERIC_OIDC` |

No configuration property is needed — the registration ID from the OAuth2 login flow is used directly.

### Keycloak Setup

See [docs/keycloak-setup.md](keycloak-setup.md) for detailed Keycloak configuration including group-based authorization and the hierarchical group path convention, or [docs/authentik-setup.md](authentik-setup.md) for authentik.

### Auto-Provisioning

When a user logs in via OAuth2 for the first time, they are automatically created in the database. This is enabled by default in the `keycloak` profile:

```yaml
epistola:
  auth:
    auto-provision: true # Default in keycloak profile
```

Disable this to require manual user creation before login.

## Configuration Properties

| Property                                  | Default            | Description                                                       |
| ----------------------------------------- | ------------------ | ----------------------------------------------------------------- |
| `epistola.auth.auto-provision`            | `true`             | Auto-provision OAuth2 users on first login                        |
| `epistola.auth.oidc.sso-button-label`     | `Sign in with SSO` | Label on the SSO login button (e.g. `Sign in with authentik`)     |
| `epistola.auth.oidc.backchannel-base-url` | _(none)_           | Internal base URL for server-to-server OIDC calls (split-horizon) |

## Session Management

Sessions are stored in PostgreSQL using Spring Session JDBC, enabling:

- **Session persistence**: Sessions survive server restarts
- **Horizontal scaling**: Multiple app instances share sessions

### How It Works

1. User authenticates (form login or OAuth2)
2. Session is created and stored in `spring_session` table
3. `JSESSIONID` cookie is sent to the browser
4. Any app instance can read the session from the database

### Session Tables

Created by Flyway migration `V10__create_spring_session_tables.sql`:

- `spring_session` - Session metadata (ID, expiry, principal name)
- `spring_session_attributes` - Serialized session data (authentication objects)

## Architecture

### Module Responsibilities

```
┌─────────────────────────────────────────────────────────────┐
│                    apps/epistola                             │
│  ┌─────────────────┐  ┌──────────────────────────────────┐  │
│  │  SecurityConfig │  │  LocalUserDetailsService         │  │
│  │  SecurityFilter │  │  OAuth2UserProvisioningService   │  │
│  │  AuthRoutes     │  │  AuthProperties                  │  │
│  │  SafetyValidator│  │                                  │  │
│  └─────────────────┘  └──────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                  modules/epistola-core                       │
│  ┌─────────────────┐  ┌──────────────────────────────────┐  │
│  │ SecurityContext │  │  User, UserId                    │  │
│  │ EpistolaPrincipal│  │  CreateUser, GetUserByExternalId │  │
│  └─────────────────┘  └──────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

- **apps/epistola**: HTTP/Spring Security concerns (filters, OAuth2, form login, safety validation)
- **epistola-core**: Domain model and business logic (User, SecurityContext)

### SecurityContext

Access the authenticated user in business logic via `SecurityContext`:

```kotlin
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.currentUser
import app.epistola.suite.security.currentUserId

// In a command/query handler
class MyHandler {
    fun handle() {
        // Get current user (throws if not authenticated)
        val user = currentUser()

        // Get just the user ID (for audit fields)
        val userId = currentUserId()

        // Check tenant access
        if (!user.hasAccessToTenant(tenantId)) {
            throw AccessDeniedException("No access to tenant")
        }
    }
}
```

The `SecurityContext` uses `ScopedValue` for virtual thread compatibility, matching the `MediatorContext` pattern.

### SecurityFilter

The `SecurityFilter` bridges Spring Security and `SecurityContext`:

1. Extracts authentication from Spring Security's `SecurityContextHolder`
2. Converts to `EpistolaPrincipal`
3. Binds to `SecurityContext` for the duration of the request

## User Model

### Database Schema

```sql
-- Users table
CREATE TABLE users (
    id UUID PRIMARY KEY,
    external_id VARCHAR(255) NOT NULL,  -- OAuth2 "sub" claim
    email VARCHAR(320) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    provider VARCHAR(50) NOT NULL,       -- KEYCLOAK, LOCAL, GENERIC_OIDC
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_login_at TIMESTAMP WITH TIME ZONE
);

-- Tenant memberships (many-to-many)
CREATE TABLE tenant_memberships (
    user_id UUID REFERENCES users(id),
    tenant_key VARCHAR(63) REFERENCES tenants(id),
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, tenant_key)
);
```

### EpistolaPrincipal

The authenticated user representation used throughout the application:

```kotlin
data class EpistolaPrincipal(
    val userId: UserId,
    val externalId: String,
    val email: String,
    val displayName: String,
    val tenantMemberships: Set<TenantId>,
    val currentTenantId: TenantId?,
)
```

## API Key Authentication

API key authentication allows machine-to-machine access to the REST API without a browser login. Each key is scoped to a single tenant **and to a chosen subset of that tenant's roles** (least privilege) — see [`authorization.md`](authorization.md#api-keys-are-least-privilege).

### Managing API Keys

API keys are managed per-tenant via the web UI at `/tenants/{tenantId}/api-keys`:

- **Create:** Name the key, pick its **scope** (the tenant roles it authenticates as — `content-viewer` is the default, administration is never pre-selected), and optionally set an expiration date. The plaintext key is shown **exactly once** — store it immediately.
- **List:** View all active keys with name, prefix, creation date, last used, and expiration.
- **Revoke:** Delete a key to immediately invalidate it.

Keys are created as **non-personal accounts (NPAs)** — the key itself becomes the actor identity for audit trails.

### Using an API Key

Include the key in every REST API request via the `Authorization` header:

```bash
curl -H "Authorization: ApiKey epk_abc123..." https://epistola.example.com/api/v1/...
```

The legacy `X-API-Key` header is still accepted for existing integrations. The
compatibility header name is configurable via `epistola.auth.api-key.header-name`
(defaults to `X-API-Key`) and is treated as an additional alias, not as a
replacement for the standard `Authorization: ApiKey` scheme.

### Key Format

- Prefix: `epk_`
- Total length: ~47 characters
- Stored as SHA-256 hash (plaintext never persisted)
- Display prefix in UI: `epk_ABC12345...` (first 8 chars after prefix)

### Expiration & Revocation

- **Expiration:** Optionally set at creation. Expired keys return 401.
- **Revocation:** Deleting a key immediately invalidates it. Disabled keys also return 401.
- **Last-used tracking:** Updated asynchronously on each authentication.

### How It Works

```
Client                    ApiKeyAuthenticationFilter            Database
  │                              │                                │
  │  Authorization: ApiKey epk_...                               │
  │ ─────────────────────────►   │                                │
  │                              │  SHA-256(key)                  │
  │                              │  LookupApiKeyByHash(hash)      │
  │                              │ ───────────────────────────►   │
  │                              │  ◄───────────────────────────  │
  │                              │                                │
  │                              │  ├─ Not found    → 401         │
  │                              │  ├─ Expired      → 401         │
  │                              │  ├─ Disabled     → 401         │
  │                              │  └─ Valid        → proceed     │
  │                              │                                │
  │  ◄─────────────────────────  │                                │
```

The filter is registered in the `/api/**` security chain. Unlike session-based auth, API key requests are **stateless** — every request is validated independently.

### Demo Shared Secret

**Demo profile only.** A single credential that authenticates **every** `/api/**` endpoint against
**every** tenant with **every** permission — a deliberate, total bypass of the tenant and permission
model, so that the demo website can call Epistola on behalf of a visitor whose tenant was created
moments earlier at login and has no API key of its own.

```bash
# >= 32 characters. Supplied only through the environment, never committed.
export EPISTOLA_DEMO_SHAREDSECRET=$(openssl rand -hex 32)

curl -H "Authorization: ApiKey $EPISTOLA_DEMO_SHAREDSECRET" \
  https://demo.epistola.app/api/tenants/any-tenant/templates
```

It rides the existing `Authorization: ApiKey` scheme, so callers and SDKs need no new code path.
Requests that do not carry it are unaffected: a real `epk_…` key, a bearer token or no credential at
all is answered by `ApiKeyAuthenticationFilter` exactly as before.

What confines it:

| Guard    | Behaviour                                                                                                                           |
| -------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| Profile  | Wired only under the `demo` profile — **not** the `epistola.demo.enabled` property, which `local` sets too.                         |
| Startup  | A secret configured in any other profile **fails the boot** (`DemoSharedSecretSafetyValidator`) rather than being silently ignored. |
| Length   | At least 32 characters, or the boot fails. It is an unauthenticated-guessable bearer credential with no rate limit in front of it.  |
| Optional | With no secret configured, the demo profile starts exactly as before and the feature does not exist.                                |

Requests are counted on `epistola.api.auth.attempts{result="demo_shared_secret"}`, and writes are
attributed to a `Demo Shared Secret` service account rather than to a real user.

Two things it deliberately does **not** do, because it is not bound to a tenant
(`currentTenantId` is null):

- `/api/mcp` — MCP tools take their tenant from the credential, so they cannot be used with it.
- The partition block of `POST /api/ping` is omitted. `ClientIdentityFilter` also still requires
  `X-EP-Node-Id` on `/api/ping` and the collect endpoint, as it does for any caller.

See [ADR 0019](adr/0019-demo-api-shared-secret.md).

### When to Use

API key auth is intended for:

- External system integrations (CI/CD, ETL pipelines, etc.)
- MCP (Model Context Protocol) clients
- Any application that needs to call the REST API without a user session

For interactive browser usage, use form login or OAuth2/OIDC instead.

## Testing

### Integration Tests

Tests run with the `test` profile which permits all requests:

```kotlin
@SpringBootTest
@ActiveProfiles("test")
class MyTest {
    // No authentication required
}
```

### Testing with Authentication Context

For tests that need an authenticated user:

```kotlin
class MyHandlerTest : CoreIntegrationTestBase() {

    @Test
    fun `my test`() {
        withTestUser {
            // SecurityContext.current() returns test user
            val result = handler.handle(command)
        }
    }
}
```

## Troubleshooting

### App Fails to Start with "No authentication mechanism configured"

No `UserDetailsService` or `ClientRegistrationRepository` bean was found. Either:

- Activate a profile with form login: `--spring.profiles.active=local` or `localauth`
- Configure OAuth2 registrations: use `keycloak` profile

### App Fails to Start with "Cannot combine 'local' or 'localauth' profile with 'prod'"

In-memory users must not be used in production. Remove the `local`/`localauth` profile from your production configuration.

### Session Lost After Restart

1. Check that `spring_session` tables exist in the database
2. Verify `EpistolaPrincipal` and related classes implement `Serializable`
3. Check for serialization errors in logs

### OAuth2 Login Fails

1. Verify Keycloak is running and accessible
2. Check client ID and secret are correct
3. Verify redirect URI matches exactly
4. Check Keycloak logs for authentication errors

### "No authenticated user in current scope"

This error means code is trying to access `SecurityContext.current()` outside of an authenticated request:

- In HTTP requests: Ensure `SecurityFilter` is running
- In background tasks: Use `SecurityContext.runWithPrincipal()` to set context
- In tests: Use `withTestUser { }` helper

### API Key Requests Return 401

1. Verify the key is correctly included as `Authorization: ApiKey <key>`; legacy `X-API-Key` is still accepted for older clients
2. Check that the key has not expired
3. Confirm the key was not revoked (check the API keys list in the UI)
4. If using a custom legacy header name, verify `epistola.auth.api-key.header-name` matches
5. Ensure the request path starts with `/api` — API key auth only applies to the API security chain
