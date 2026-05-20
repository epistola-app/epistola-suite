# Keycloak Setup Guide

This document explains how to configure Keycloak for use with Epistola Suite.

## Overview

Epistola uses Keycloak's **Group Membership Mapper** to derive tenant roles, global roles, and platform roles from hierarchical group paths in the JWT `groups` claim. All Epistola groups live under the `/epistola` root group.

## Keycloak Roles vs Groups

Keycloak has two user assignment mechanisms. Epistola uses **groups**, not realm roles.

| Concept         | What it is                                                                                                                     | Used by                             |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------- |
| **Realm roles** | Flat labels (e.g., `ROLE_USER`, `ROLE_ADMIN`). Spring Security auto-maps them to `GrantedAuthority`.                           | Valtimo (for its own authorization) |
| **Groups**      | Organizational containers for users. No built-in Spring Security meaning — require a **protocol mapper** to appear in the JWT. | Epistola (via `groups` claim)       |

**Why groups instead of roles?** Groups support hierarchical tenant-scoped authorization. With flat realm roles, you'd need a separate role per tenant per permission, which doesn't scale. Hierarchical groups make the tenant/role structure explicit and navigable in the Keycloak admin UI.

**How it works:**

1. Admin assigns users to Keycloak groups (e.g., `/epistola/tenants/acme-corp/reader`)
2. The `oidc-group-membership-mapper` (with `full.path=true`) puts full group paths into the JWT `groups` claim
3. Epistola's `parseGroupMemberships()` extracts tenant roles, global roles, and platform roles
4. The `EpistolaPrincipal` is constructed with the parsed memberships

## Group Hierarchy

All Epistola groups live under a single root group:

```
/epistola
  /epistola/tenants                          <- tenant container
    /epistola/tenants/{tenant}               <- one per tenant
      /epistola/tenants/{tenant}/reader
      /epistola/tenants/{tenant}/editor
      /epistola/tenants/{tenant}/generator
      /epistola/tenants/{tenant}/manager
  /epistola/global                           <- global roles (all tenants)
    /epistola/global/reader
    /epistola/global/editor
    /epistola/global/generator
    /epistola/global/manager
  /epistola/platform                         <- platform roles
    /epistola/platform/tenant-manager
```

### Group Path Convention

| Pattern                              | Example                              | Meaning                          |
| ------------------------------------ | ------------------------------------ | -------------------------------- |
| `/epistola/tenants/{tenant}/{role}`  | `/epistola/tenants/acme-corp/reader` | `reader` role in `acme-corp`     |
| `/epistola/global/{role}`            | `/epistola/global/reader`            | `reader` role in **all** tenants |
| `/epistola/platform/{platform-role}` | `/epistola/platform/tenant-manager`  | Platform role: manage tenants    |

### Known Roles

**Tenant roles** (per-tenant or global):

- `reader` — view templates, themes, documents
- `editor` — edit templates and themes
- `generator` — generate documents
- `manager` — full tenant management (publish, settings, users)

**Platform roles**:

- `tenant-manager` — create and manage tenants across the platform

### Permissions

Tenant roles map to fine-grained permissions in application code (not in Keycloak):

| Role        | Permissions                                           |
| ----------- | ----------------------------------------------------- |
| `reader`    | `TEMPLATE_VIEW`, `DOCUMENT_VIEW`, `THEME_VIEW`        |
| `editor`    | `TEMPLATE_EDIT`, `THEME_EDIT`                         |
| `generator` | `DOCUMENT_GENERATE`                                   |
| `manager`   | `TEMPLATE_PUBLISH`, `TENANT_SETTINGS`, `TENANT_USERS` |

Roles are **composable** — a user's effective permissions are the union of all their roles. For example, a user with `reader` + `editor` can view and edit templates and themes.

### How Parsing Works

The JWT `groups` claim contains full paths (e.g., `/epistola/tenants/demo/reader`). The parser splits on `/` and routes based on the category segment:

- `/epistola/tenants/{tenant}/{role}` → per-tenant role
- `/epistola/global/{role}` → global tenant role
- `/epistola/platform/{role}` → platform role
- Anything else → ignored

This is unambiguous because the path structure encodes the category explicitly. Tenant keys cannot use the reserved names `tenants`, `global`, or `platform`.

### Global Roles

A global role like `/epistola/global/reader` grants read access to **all** tenants. Global roles are merged with per-tenant roles. Example:

```
User groups: ["/epistola/tenants/acme-corp/editor", "/epistola/global/reader"]

Effective roles:
  acme-corp: {READER, EDITOR}   (per-tenant EDITOR + global READER)
  any-tenant: {READER}          (from global)
```

## Keycloak Configuration

### 1. Client Setup

Create an OpenID Connect client named `epistola-suite`:

- Client authentication: ON (confidential)
- Standard flow: ON
- Service accounts: ON (needed for tenant group provisioning)
- Redirect URIs: your app URL (e.g., `http://localhost:4000/*`)

### 2. Group Membership Mapper

This can be configured manually via the admin UI (steps below), or auto-provisioned by the
app — see [Automatic Client Mapper Provisioning](#automatic-client-mapper-provisioning) for the
managed-Keycloak setup.

Add a protocol mapper to the `epistola-suite` client:

| Setting             | Value              |
| ------------------- | ------------------ |
| Name                | `epistola-groups`  |
| Mapper type         | `Group Membership` |
| Token claim name    | `groups`           |
| Full group path     | **ON**             |
| Add to ID token     | ON                 |
| Add to access token | ON                 |
| Add to userinfo     | ON                 |

**Important:** `Full group path` must be ON so the JWT contains full paths like `/epistola/tenants/demo/reader`.

### 3. Create Groups

Create the hierarchical group structure:

1. Create root group `epistola`
2. Under `epistola`, create `tenants`, `global`, and `platform`
3. Under `tenants`, create a sub-group for each tenant (e.g., `demo`)
4. Under each tenant, create role groups: `reader`, `editor`, `generator`, `manager`
5. Under `global`, create: `reader`, `editor`, `generator`, `manager`
6. Under `platform`, create: `tenant-manager`

Alternatively, configure the chart with `oidc.enabled: true` and `keycloakAdmin.ensureGroups: true` to have the app create the base structure automatically on startup (see below).

### 4. Assign Users to Groups

Assign users to the leaf groups (e.g., `/epistola/tenants/demo/reader`). Assigning to intermediate groups (e.g., `/epistola/tenants/demo`) has no effect on authorization.

## Automatic Tenant Provisioning

When the `keycloakAdmin` client secret is configured, Epistola automatically creates hierarchical Keycloak groups when a new tenant is created via the UI. The four role groups are created under `/epistola/tenants/{key}/`:

- `/epistola/tenants/{key}/reader`
- `/epistola/tenants/{key}/editor`
- `/epistola/tenants/{key}/generator`
- `/epistola/tenants/{key}/manager`

When a tenant is deleted, the entire `/epistola/tenants/{key}` group is removed (Keycloak cascades to sub-groups).

### Base Group Initialization

For environments without a realm import (e.g., production with an externally managed Keycloak), enable automatic creation of the base group hierarchy on startup:

```yaml
oidc:
  enabled: true
  clientId: epistola-suite
  issuerUri: https://keycloak.example.com/realms/epistola
  existingSecret: epistola-keycloak-client

keycloakAdmin:
  enabled: true
  adminUrl: https://keycloak.example.com
  realm: epistola
  clientId: epistola-suite
  existingSecret: epistola-keycloak-client
  ensureGroups: true # disabled by default
```

This creates `/epistola/tenants`, `/epistola/global/*`, and `/epistola/platform/*` if they don't exist. The operation is idempotent.

### Automatic Client Mapper Provisioning

With `ensureGroups: true`, Epistola also ensures the Group Membership protocol mapper on its
own client (`clientId`) is configured correctly on startup. This removes a manual setup step
and prevents misconfigured deployments where the `groups` claim is missing or contains
short-name groups (without `full.path=true`).

What it does:

- If no mapper writing `claim.name=groups` exists on the client → creates one named
  `epistola-groups` with `full.path=true`, included in ID/access/userinfo tokens.
- If a mapper named `epistola-groups` already exists but its config has drifted → updates it
  back to the canonical config (self-heal).
- If a mapper with a _different_ name already writes `claim.name=groups` → logs a WARN and
  leaves it alone, so deliberate operator config is not clobbered. Inspect the warning and
  reconcile manually if the `groups` claim isn't behaving as expected.

If the service account is missing the `manage-clients` realm-management role, the app logs a
warning and the rest of startup continues. In that case, configure the mapper manually
(Step 2 above) or grant the role.

### Configuration

```yaml
oidc:
  enabled: true
  clientId: epistola-suite
  issuerUri: http://localhost:8080/realms/epistola
  existingSecret: epistola-keycloak-client

keycloakAdmin:
  enabled: true
  adminUrl: http://localhost:8080 # Keycloak admin base URL
  realm: epistola # Realm name
  clientId: epistola-suite # Client with service account
  existingSecret: epistola-keycloak-client
  ensureGroups: false # Create base group hierarchy on startup
```

The `epistola-suite` client's service account needs `realm-management` client roles:

- `manage-users` — required for creating and deleting groups
- `view-users` — required for listing groups
- `query-users` — required for searching groups
- `manage-clients` — required when `ensureGroups: true` so the app can manage its own
  client's Group Membership protocol mapper (see [Automatic Client Mapper Provisioning](#automatic-client-mapper-provisioning))

## Local Development

Start Keycloak with the provided Docker Compose setup:

```bash
docker compose -f apps/epistola/docker/docker-compose.yaml up -d
```

Run the app with the `keycloak` profile:

```bash
./gradlew :apps:epistola:bootRun --args='--spring.profiles.active=local,keycloak'
```

### Test Users

Pre-configured in the realm export (`apps/epistola/docker/keycloak/realm-export.json`):

| Username         | Password    | Roles on `demo` tenant | Platform roles                      |
| ---------------- | ----------- | ---------------------- | ----------------------------------- |
| `admin@demo`     | `admin`     | All roles              | `tenant-manager` + all global roles |
| `reader@demo`    | `reader`    | Reader                 | —                                   |
| `editor@demo`    | `editor`    | Reader, Editor         | —                                   |
| `generator@demo` | `generator` | Reader, Generator      | —                                   |
| `manager@demo`   | `manager`   | All tenant roles       | —                                   |

Self-registration is enabled — new users can register with email + password. In demo mode (`epistola.demo.enabled=true`), users without group memberships are automatically assigned to a tenant derived from their email domain (e.g., `user@acme.io` → tenant `acme-io` with all roles).

### OIDC Logout

Logging out of Epistola also ends the Keycloak SSO session (via OIDC RP-Initiated Logout), so the next login shows the Keycloak login form instead of auto-authenticating.

## JWT Claim Example

After login, the JWT will contain:

```json
{
  "groups": [
    "/epistola/tenants/demo/reader",
    "/epistola/tenants/demo/editor",
    "/epistola/tenants/demo/generator",
    "/epistola/tenants/demo/manager",
    "/epistola/platform/tenant-manager"
  ]
}
```

## Using a Non-Keycloak IDP (BYO IDP)

Epistola's `GroupMembershipParser` expects the `groups` claim to contain **full
hierarchical paths** matching `/epistola/{tenants|global|platform}/...`. Most enterprise
IdPs (Entra ID, Okta, ADFS, etc.) do not produce hierarchical group claims natively — they
emit flat group names or object IDs.

The supported integration pattern is therefore:

```
[ Customer IDP (Entra/Okta/ADFS) ]
            │
            │ OIDC / SAML identity brokering
            ▼
[ Keycloak ] ── issues tokens to ──► [ Epistola Suite ]
   (broker)                                  ▲
                                             │
                                Token contains `/epistola/...`
                                groups via Group Membership mapper
```

Keycloak acts as a broker: it federates authentication to the customer's IDP, then maps
the brokered identity onto Epistola's group structure inside the realm. The application
itself only ever sees Keycloak-issued tokens with the expected claim shape.

### Setup outline

1. In the Epistola Keycloak realm, configure the customer's IDP under **Identity Providers**
   (OIDC v1 / SAML v2). Trust is established with the customer's IDP admins.
2. Add identity-provider **claim mappers** that derive Epistola group memberships from
   whatever the customer IDP emits — typically:
   - Static group assignment, e.g. all brokered users land in `/epistola/global/reader`.
   - Conditional / attribute-based assignment, e.g. users in the IDP's "Tenant Admins"
     group are mapped into `/epistola/platform/tenant-manager`.
3. Users authenticate via their corporate IDP. Keycloak provisions a local shadow user on
   first login and issues a token that conforms to the contract documented above.

### Token contract (read this first)

For any IDP integration, the token Epistola Suite receives must contain:

| Claim    | Type            | Example values                                                                                                                                                                                   |
| -------- | --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `sub`    | string          | stable user identifier                                                                                                                                                                           |
| `email`  | string          | user's email                                                                                                                                                                                     |
| `groups` | array of string | `["/epistola/tenants/acme-corp/reader", "/epistola/platform/tenant-manager"]` — paths must start with `/epistola/` and follow the conventions in [Group Path Convention](#group-path-convention) |

Anything outside the `/epistola/` prefix is ignored by the parser. Short-name groups
(e.g. `"tenant-manager"` without the path prefix) are also ignored — `full.path=true` on
the Group Membership mapper is what produces the expected shape.

### When to keep Keycloak in the loop vs. integrate directly

Direct integration (no Keycloak broker) is technically possible if the customer's IDP can
emit a `groups` claim with full Epistola-style paths. In practice this is rare — corporate
IDP teams are reluctant to model another app's authorization hierarchy inside their
identity store, and the result is brittle. The brokered pattern keeps Epistola's group
model owned by the app deployment, while letting the customer's IDP own authentication.
