# Keycloak Setup Guide

This document explains how to configure Keycloak for use with Epistola Suite.

## Overview

Epistola uses Keycloak's **Group Membership Mapper** to derive tenant roles, global roles, and platform roles from JWT group names. All Epistola groups use the `ep_` prefix.

## Keycloak Roles vs Groups

Keycloak has two user assignment mechanisms. Epistola uses **groups**, not realm roles.

| Concept         | What it is                                                                                                                     | Used by                             |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------- |
| **Realm roles** | Flat labels (e.g., `ROLE_USER`, `ROLE_ADMIN`). Spring Security auto-maps them to `GrantedAuthority`.                           | Valtimo (for its own authorization) |
| **Groups**      | Organizational containers for users. No built-in Spring Security meaning — require a **protocol mapper** to appear in the JWT. | Epistola (via `groups` claim)       |

**Why groups instead of roles?** Groups support the `ep_{tenant}_{role}` naming convention. With flat realm roles, you'd need a separate role per tenant per permission, which doesn't scale. Groups allow tenant-scoped authorization by convention.

**How it works:**

1. Admin assigns users to Keycloak groups (e.g., `ep_acme_reader`)
2. The `oidc-group-membership-mapper` puts group names into the JWT `groups` claim
3. Epistola's `parseGroupMemberships()` extracts tenant roles, global roles, and platform roles
4. The `EpistolaPrincipal` is constructed with the parsed memberships

## Group Naming Convention

| Pattern              | Example               | Meaning                          |
| -------------------- | --------------------- | -------------------------------- |
| `ep_{tenant}_{role}` | `ep_acme-corp_reader` | `reader` role in `acme-corp`     |
| `ep_{role}`          | `ep_reader`           | `reader` role in **all** tenants |
| `ep_tenant-manager`  | `ep_tenant-manager`   | Platform role: manage tenants    |

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

After stripping the `ep_` prefix:

- Contains `_` → split on **last** `_` → left = tenant key, right = role
- No `_` → global tenant role or platform role

This is unambiguous because tenant keys match `^[a-z][a-z0-9]*(-[a-z0-9]+)*$` (no underscores).

### Global Roles

A global role like `ep_reader` grants read access to **all** tenants. Global roles are merged with per-tenant roles. Example:

```
User groups: ["ep_acme-corp_editor", "ep_reader"]

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

Add a protocol mapper to the `epistola-suite` client:

| Setting             | Value                     |
| ------------------- | ------------------------- |
| Name                | `group-membership-mapper` |
| Mapper type         | `Group Membership`        |
| Token claim name    | `groups`                  |
| Full group path     | OFF                       |
| Add to ID token     | ON                        |
| Add to access token | ON                        |
| Add to userinfo     | ON                        |

### 3. Create Groups

For each tenant, create groups:

- `ep_{tenant-key}_reader`
- `ep_{tenant-key}_editor`
- `ep_{tenant-key}_generator`
- `ep_{tenant-key}_manager`

For platform roles:

- `ep_tenant-manager`

For global roles (optional):

- `ep_reader`, `ep_editor`, etc.

### 4. Assign Users to Groups

Assign users to the appropriate groups based on their roles.

## Automatic Tenant Provisioning

When `epistola.keycloak.client-secret` is configured, Epistola automatically creates Keycloak groups when a new tenant is created via the UI. The four standard groups (`ep_{key}_reader`, `ep_{key}_editor`, `ep_{key}_generator`, `ep_{key}_manager`) are created automatically.

### Configuration

```yaml
epistola:
  keycloak:
    admin-url: http://localhost:8080 # Keycloak base URL
    realm: epistola # Realm name
    client-id: epistola-suite # Client with service account
    client-secret: ${KEYCLOAK_CLIENT_SECRET} # Client secret
```

The `epistola-suite` client's service account needs `realm-management` client roles:

- `manage-users` — required for creating and deleting groups
- `view-users` — required for listing groups
- `query-users` — required for searching groups

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
    "ep_demo_reader",
    "ep_demo_editor",
    "ep_demo_generator",
    "ep_demo_manager",
    "ep_tenant-manager"
  ]
}
```
