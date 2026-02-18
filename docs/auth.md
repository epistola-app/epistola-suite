# Authentication

Epistola Suite uses **bean-driven authentication** that adapts to the runtime environment based on which Spring beans are present:

| Bean Present | Authentication Method | Provided By |
|-------------|----------------------|-------------|
| `UserDetailsService` | Form-based login with in-memory users | `LocalUserDetailsService` (`local` / `demo` profiles) |
| `ClientRegistrationRepository` | OAuth2/OIDC (Keycloak, etc.) | Spring Security auto-config from `application-prod.yaml` or `application-keycloak.yaml` |
| Neither | **Startup failure** — safety validator blocks | — |

## How It Works

Authentication methods are **not determined by profile name checks**. Instead:

1. **`LocalUserDetailsService`** is annotated `@Profile("local | demo")` — it's the single source of truth for which profiles get form login.
2. **`SecurityConfig`** and **`LoginHandler`** check for `UserDetailsService` bean presence (not profile names).
3. **`OAuth2UserProvisioningService`** uses `@ConditionalOnBean(ClientRegistrationRepository::class)` — only loaded when OAuth2 is configured.

Adding a new form-login profile only requires updating `LocalUserDetailsService`'s `@Profile` annotation.

## Safety Guards

### AuthenticationSafetyValidator

A `SmartInitializingSingleton` that runs at startup and fails fast if:

- **In-memory users in production**: `local` or `demo` profile combined with `prod` → blocks startup (known passwords in production).
- **No authentication configured**: Neither `UserDetailsService` nor `ClientRegistrationRepository` exists → blocks startup (all requests would 403).

Skipped in `test` profile (tests use permit-all security).

### UserDetailsServiceAutoConfiguration Excluded

Spring Boot's `UserDetailsServiceAutoConfiguration` is excluded to prevent it from creating a default `InMemoryUserDetailsManager` with a random password when no profile provides a `UserDetailsService`. This ensures the safety validator catches the "no auth" case.

## Local Development

Start the application with the `local` profile:

```bash
./gradlew :apps:epistola:bootRun --args='--spring.profiles.active=local'
```

### Test Accounts

| Username | Password | Description |
|----------|----------|-------------|
| `admin@local` | `admin` | Admin user with access to all tenants |
| `user@local` | `user` | Regular user with access to demo-tenant |

## Demo Environment

The `demo` profile provides the same in-memory users as `local`, suitable for K8s demo environments:

```bash
SPRING_PROFILES_ACTIVE=demo
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

Or configure in `application-prod.yaml`:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-id: ${KEYCLOAK_CLIENT_ID}
            client-secret: ${KEYCLOAK_CLIENT_SECRET}
            scope: openid,profile,email
        provider:
          keycloak:
            issuer-uri: ${KEYCLOAK_ISSUER_URI}
```

### AuthProvider Derivation

The `AuthProvider` stored on `User` records is derived from the OAuth2 registration ID:

| Registration ID | AuthProvider |
|----------------|-------------|
| `keycloak` | `KEYCLOAK` |
| anything else | `GENERIC_OIDC` |

No configuration property is needed — the registration ID from the OAuth2 login flow is used directly.

### Keycloak Setup

1. Create a realm (e.g., `epistola`)
2. Create a client with:
   - Client ID: `epistola-suite`
   - Client authentication: On
   - Valid redirect URIs: `https://your-app.com/login/oauth2/code/keycloak`
   - Web origins: `https://your-app.com`
3. Create users in Keycloak

### Auto-Provisioning

When a user logs in via OAuth2 for the first time, they are automatically created in the database if `auto-provision` is enabled:

```yaml
epistola:
  auth:
    auto-provision: true  # Default in prod
```

Disable this to require manual user creation before login.

## Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `epistola.auth.auto-provision` | `true` | Auto-provision OAuth2 users on first login |

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
    tenant_id VARCHAR(63) REFERENCES tenants(id),
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, tenant_id)
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
- Activate a profile with form login: `--spring.profiles.active=local` or `demo`
- Configure OAuth2 registrations: use `prod` or `keycloak` profile

### App Fails to Start with "Cannot combine 'local' or 'demo' profile with 'prod'"

In-memory users with known passwords must not be used in production. Remove the `local`/`demo` profile from your production configuration.

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
