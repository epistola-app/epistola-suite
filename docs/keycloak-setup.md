# Keycloak Setup Guide

This document explains how to configure Keycloak for use with Epistola Suite.

## Overview

Epistola accepts memberships from **two JWT claim shapes**, in parallel — pick whichever
your IdP can produce; the app reads both and takes the union:

1. **Hierarchical groups** (`/epistola/...` paths in the `groups` claim) — the recommended
   path when you control Keycloak. Mapped via Keycloak's **Group Membership Mapper**.
2. **Flat roles** (prefix-encoded strings in a configurable claim, default `roles`) — for
   IdPs that cannot emit hierarchical groups (Auth0, Cognito, AD-federated, …). Mapped
   via Keycloak's **Realm Role Mapper**, or by any IdP that can put a list of role labels
   in a JWT claim.

Both mappers are auto-provisioned on startup when running against Keycloak (see
[Automatic Client Mapper Provisioning](#automatic-client-mapper-provisioning)).

## Choosing a claim shape

| Concept                 | What it is                                                                                                   | When to use it                                          |
| ----------------------- | ------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------- |
| **Hierarchical groups** | Keycloak group paths under `/epistola/...` — surfaced as `groups: [String]` via the Group Membership mapper. | Default for Keycloak deployments; navigable in admin UI |
| **Flat roles**          | Prefix-encoded strings (`epg_*`, `ept_*_*`, `eps_*`) in a configurable claim, default `roles`.               | IdPs that emit only flat realm roles or scopes          |

Both produce the same effective memberships. A user can be granted access via either
mechanism, or both — duplicates are de-duped via set union.

**How it works:**

1. Admin assigns users to Keycloak groups (e.g., `/epistola/tenants/acme-corp/reader`)
   **and/or** to realm roles (e.g., `ept_acme-corp_reader`).
2. The `oidc-group-membership-mapper` (with `full.path=true`) puts full group paths into
   the JWT `groups` claim; the `oidc-usermodel-realm-role-mapper` puts realm role names
   into the configured flat-roles claim (default `roles`).
3. Epistola's `parseGroupMemberships()` and `parseFlatRoles()` each extract tenant
   roles, global roles, and platform roles. The two results are merged.
4. The `EpistolaPrincipal` is constructed with the union of parsed memberships.

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

## Flat Roles (for IdPs without groups)

When an IdP cannot emit hierarchical group paths, Epistola accepts a **flat string-array
claim** (default name `roles`, configurable via `epistola.auth.flat-roles.claim-name`).
Each entry uses one of three prefixes:

| Prefix | Pattern                  | Example                | Meaning                                     |
| ------ | ------------------------ | ---------------------- | ------------------------------------------- |
| `epg_` | `epg_<role>`             | `epg_reader`           | Global tenant role (applies to all tenants) |
| `ept_` | `ept_<tenantKey>_<role>` | `ept_acme-corp_editor` | Per-tenant role                             |
| `eps_` | `eps_<platformRole>`     | `eps_tenant_manager`   | Platform role                               |

Role names match the existing vocabulary: `reader`, `editor`, `generator`, `manager`
(tenant roles) and `tenant_manager` (platform role — `_` is normalised to `-` for lookup,
so both `eps_tenant_manager` and `eps_tenant-manager` resolve correctly). Tenant keys
follow the existing slug rules (lowercase letters/digits/hyphens, never underscores), so
`_` is unambiguously the segment separator.

Strings that don't match a prefix, or that reference an unknown role / invalid tenant
key, are silently ignored — so unrelated IdP roles (`ROLE_USER`, etc.) don't break login.

**Configuration:**

```yaml
epistola:
  auth:
    flat-roles:
      claim-name: roles # default; override if your IdP emits a different claim
```

Memberships from `groups` and from the flat-roles claim are merged (union), so an
operator can mix-and-match — useful during migrations or for users granted roles via
different mechanisms.

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

With `ensureGroups: true`, Epistola ensures **two** protocol mappers exist on its own
client (`clientId`) on startup, so the JWT carries both supported claim shapes out of
the box:

- `epistola-groups` — Group Membership mapper writing `claim.name=groups`, with
  `full.path=true`, included in ID/access/userinfo tokens.
- `epistola-realm-roles` — User Realm Role mapper writing the configured flat-roles
  claim (default `roles`), `multivalued=true`, included in ID/access/userinfo tokens.

For each mapper:

- If no mapper writing the target claim exists → creates the canonical one.
- If a mapper with the canonical name exists but its config has drifted → updates it
  back to the canonical config (self-heal).
- If a mapper with a _different_ name already writes the target claim → logs a WARN and
  leaves it alone, so deliberate operator config is not clobbered.

If the service account is missing the `manage-clients` realm-management role, the app
logs a warning and the rest of startup continues. In that case, configure the mappers
manually (see [Group Membership Mapper](#2-group-membership-mapper) above) or grant the
role.

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

After login against an auto-provisioned Keycloak, the JWT contains both shapes for the
same effective memberships:

```json
{
  "groups": [
    "/epistola/tenants/demo/reader",
    "/epistola/tenants/demo/editor",
    "/epistola/tenants/demo/generator",
    "/epistola/tenants/demo/manager",
    "/epistola/platform/tenant-manager"
  ],
  "roles": [
    "ept_demo_reader",
    "ept_demo_editor",
    "ept_demo_generator",
    "ept_demo_manager",
    "eps_tenant_manager"
  ]
}
```

An IdP that emits only one of these — say, only `roles` — is fully supported. The
unused parser simply contributes the empty membership set.

## Using a Non-Keycloak IDP (BYO IDP)

Two paths are supported:

1. **Flat roles directly from the customer IDP** — if the IDP can emit a flat string-array
   claim, map the relevant roles to the `epg_*` / `ept_*_*` / `eps_*` convention
   (see [Flat Roles](#flat-roles-for-idps-without-groups)) and point
   `epistola.auth.flat-roles.claim-name` at it. No Keycloak in the loop.
2. **Keycloak as a broker** — the customer IDP federates into Keycloak, which then maps
   the brokered identity onto Epistola's group structure. Recommended when the customer
   IDP team doesn't want to model another app's authorization hierarchy.

Option 1 is the simplest path when it works. Option 2 (described below) is the
historical recommendation and remains useful when the customer IDP can't emit the
required claim shape.

### Brokered pattern (recommended for complex enterprise IdPs)

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

For any IDP integration, the token Epistola Suite receives must contain `sub` and
`email`, plus **at least one** of the following claim shapes:

| Claim                                  | Type            | Example values                                                                                                                                                                                   |
| -------------------------------------- | --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `groups`                               | array of string | `["/epistola/tenants/acme-corp/reader", "/epistola/platform/tenant-manager"]` — paths must start with `/epistola/` and follow the conventions in [Group Path Convention](#group-path-convention) |
| `roles` (or the configured equivalent) | array of string | `["ept_acme-corp_reader", "eps_tenant_manager"]` — see [Flat Roles](#flat-roles-for-idps-without-groups)                                                                                         |

Anything outside the recognised prefixes / paths is ignored by the parsers — short-name
groups (e.g. `"tenant-manager"` without the path prefix) and unknown role labels alike.
Both claims can be present at once; their results are merged.

### When to keep Keycloak in the loop vs. integrate directly

Direct integration is straightforward when the customer IDP can emit a flat
prefix-encoded `roles` claim — see Option 1 above. Direct integration with the
hierarchical `groups` shape is also possible but rare, because corporate IDP teams are
reluctant to model another app's authorization hierarchy inside their identity store.
The brokered pattern keeps Epistola's group model owned by the app deployment, while
letting the customer's IDP own authentication.
