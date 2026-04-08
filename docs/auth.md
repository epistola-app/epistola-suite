# Authentication

Epistola Suite uses **bean-driven authentication** that adapts to the runtime environment based on which Spring beans are present:

| Bean Present                   | Authentication Method                         | Provided By                                                  |
| ------------------------------ | --------------------------------------------- | ------------------------------------------------------------ |
| `UserDetailsService`           | Form-based login with configurable users      | `LocalUserDetailsService` (`local` / `localauth` profiles)   |
| `ClientRegistrationRepository` | OAuth2/OIDC (Keycloak, etc.)                  | Spring Security auto-config from `application-keycloak.yaml` |
| Neither                        | **Startup failure** — safety validator blocks | —                                                            |

## How It Works

Authentication methods are **not determined by profile name checks**. Instead:

1. **`LocalUserDetailsService`** is annotated `@Profile("local | localauth")` — it's the single source of truth for which profiles get form login.
2. **`SecurityConfig`** and **`LoginHandler`** check for `UserDetailsService` bean presence (not profile names).
3. **`OAuth2UserProvisioningService`** uses `@ConditionalOnBean(ClientRegistrationRepository::class)` — only loaded when OAuth2 is configured.

Adding a new form-login profile only requires updating `LocalUserDetailsService`'s `@Profile` annotation.

## Profile Composition

Profiles are orthogonal — each controls a single concern:

| Profile     | Concern                                                                                     |
| ----------- | ------------------------------------------------------------------------------------------- |
| `local`     | Dev experience: devtools, filesystem serving, editor watch. Implies form login + demo data. |
| `localauth` | Form login with configurable users (env-var overridable)                                    |
| `keycloak`  | OAuth2/OIDC authentication via Keycloak                                                     |
| `demo`      | Load demo data only (no auth side effects)                                                  |
| `prod`      | Production hardening: flyway clean disabled, tuned concurrency                              |

### Environment Matrix

| Environment  | Profiles                  | Auth         | Demo | DB Reset |
| ------------ | ------------------------- | ------------ | ---- | -------- |
| Local dev    | `local`                   | Form login   | yes  | yes      |
| Local + KC   | `local,keycloak`          | Form + OAuth | yes  | yes      |
| Test/Staging | `keycloak,demo`           | OAuth2 only  | yes  | yes      |
| Test/Staging | `keycloak,demo,localauth` | Both         | yes  | yes      |
| Production   | `prod,keycloak`           | OAuth2 only  | no   | no       |

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

The `localauth` profile provides form-based login with **configurable** users, suitable for staging/test environments where you need form login alongside Keycloak:

```bash
SPRING_PROFILES_ACTIVE=keycloak,demo,localauth
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

The `demo` profile **only** loads demo data (tenant, themes, templates). It does not affect authentication. Combine it with an auth profile:

```bash
# OAuth2 + demo data
SPRING_PROFILES_ACTIVE=keycloak,demo

# OAuth2 + form login + demo data
SPRING_PROFILES_ACTIVE=keycloak,demo,localauth
```

## Production (OAuth2/OIDC)

Production uses OAuth2/OIDC with Keycloak (or any OIDC-compliant provider).

### Configuration

Set these environment variables:

```bash
export KEYCLOAK_CLIENT_ID=epistola-suite
export KEYCLOAK_CLIENT_SECRET=<your-secret>
export KEYCLOAK_ISSUER_URI=https://keycloak.example.com/realms/epistola
```

Activate profiles:

```bash
SPRING_PROFILES_ACTIVE=prod,keycloak
```

The `keycloak` profile provides all OAuth2 configuration with env-var overrides. The `prod` profile provides production hardening (flyway clean disabled, tuned concurrency).

### AuthProvider Derivation

The `AuthProvider` stored on `User` records is derived from the OAuth2 registration ID:

| Registration ID | AuthProvider   |
| --------------- | -------------- |
| `keycloak`      | `KEYCLOAK`     |
| anything else   | `GENERIC_OIDC` |

No configuration property is needed — the registration ID from the OAuth2 login flow is used directly.

### Keycloak Setup

See [docs/keycloak-setup.md](keycloak-setup.md) for detailed Keycloak configuration including group-based authorization, the hierarchical group path convention, and automatic tenant provisioning.

### Auto-Provisioning

When a user logs in via OAuth2 for the first time, they are automatically created in the database. This is enabled by default in the `keycloak` profile:

```yaml
epistola:
  auth:
    auto-provision: true # Default in keycloak profile
```

Disable this to require manual user creation before login.

## Configuration Properties

| Property                       | Default | Description                                |
| ------------------------------ | ------- | ------------------------------------------ |
| `epistola.auth.auto-provision` | `true`  | Auto-provision OAuth2 users on first login |

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
