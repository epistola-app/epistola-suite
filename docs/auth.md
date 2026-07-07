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
| `demo`      | Load demo data only (no auth side effects)                                                                                                                                   |
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

The `demo` profile **only** loads demo data (tenant, themes, templates). It does not affect authentication. In staging/production, OIDC is supplied by env vars (Helm), so `demo` is combined with `prod` and/or `localauth` rather than a `keycloak` profile:

```bash
# OAuth2 (from env vars) + demo data
SPRING_PROFILES_ACTIVE=demo

# OAuth2 + form login + demo data
SPRING_PROFILES_ACTIVE=demo,localauth
```

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

Include the key in every REST API request via the `X-API-Key` header:

```bash
curl -H "X-API-Key: epk_abc123..." https://epistola.example.com/api/v1/...
```

The header name is configurable via `epistola.auth.api-key.header-name` (defaults to `X-API-Key`).

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
  │  X-API-Key: epk_...          │                                │
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

1. Verify the key is correctly included in the `X-API-Key` header (not `Authorization: Bearer`)
2. Check that the key has not expired
3. Confirm the key was not revoked (check the API keys list in the UI)
4. If using a custom header name, verify `epistola.auth.api-key.header-name` matches
5. Ensure the request path starts with `/api` — API key auth only applies to the API security chain
