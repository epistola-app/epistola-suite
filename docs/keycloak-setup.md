# Keycloak Setup Guide

This document explains how to configure Keycloak for use with Epistola Suite.

## Overview

Epistola accepts memberships from **two JWT claim shapes**, in parallel — pick whichever
your IdP can produce; the app reads both and takes the union:

1. **Hierarchical groups** (`/epistola/...` paths in the `groups` claim) — a navigable group
   tree. Mapped via Keycloak's **Group Membership Mapper**.
2. **Flat roles** (prefix-encoded strings in a configurable claim, default `roles`) — also
   the path for IdPs that cannot emit hierarchical groups (Auth0, Cognito, AD-federated, …).
   On Keycloak the claim can be fed by either **client roles** (recommended — keeps the
   Epistola model scoped to its own client) or **realm roles**; any IdP that can put a list
   of role labels in a JWT claim works too.

If you control Keycloak, the recommended setup is **client roles → flat `roles` claim** —
see [Recommended: client roles (step by step)](#recommended-client-roles-step-by-step).

The realm-role and group mappers are auto-provisioned on startup when running against
Keycloak (see [Automatic Client Mapper Provisioning](#automatic-client-mapper-provisioning));
the client-role mapper is the one manual step.

## Choosing a claim shape

| Concept                 | What it is                                                                                                   | When to use it                                          |
| ----------------------- | ------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------- |
| **Hierarchical groups** | Keycloak group paths under `/epistola/...` — surfaced as `groups: [String]` via the Group Membership mapper. | Default for Keycloak deployments; navigable in admin UI |
| **Flat roles**          | Prefix-encoded strings (`epg_*`, `ept_*_*`, `eps_*`) in a configurable claim, default `roles`.               | IdPs that emit only flat realm roles or scopes          |

Both produce the same effective memberships. A user can be granted access via either
mechanism, or both — duplicates are de-duped via set union.

**How it works:**

1. Admin assigns users to Keycloak groups (e.g., `/epistola/tenants/acme-corp/content-viewer`)
   **and/or** to realm roles (e.g., `ept_acme-corp_content-viewer`).
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
      /epistola/tenants/{tenant}/content-viewer
      /epistola/tenants/{tenant}/content-author
      /epistola/tenants/{tenant}/document-generator
      /epistola/tenants/{tenant}/content-publisher
      /epistola/tenants/{tenant}/tenant-administrator
  /epistola/global                           <- global roles (all tenants)
    /epistola/global/content-viewer
    /epistola/global/content-author
    /epistola/global/document-generator
    /epistola/global/content-publisher
    /epistola/global/tenant-administrator
  /epistola/platform                         <- platform roles
    /epistola/platform/tenant-manager
    /epistola/platform/platform-observer
```

### Group Path Convention

| Pattern                              | Example                                      | Meaning                                  |
| ------------------------------------ | -------------------------------------------- | ---------------------------------------- |
| `/epistola/tenants/{tenant}/{role}`  | `/epistola/tenants/acme-corp/content-viewer` | `content-viewer` role in `acme-corp`     |
| `/epistola/global/{role}`            | `/epistola/global/content-viewer`            | `content-viewer` role in **all** tenants |
| `/epistola/platform/{platform-role}` | `/epistola/platform/tenant-manager`          | Platform role: manage tenants            |

### Known Roles

**Tenant roles** (per-tenant or global):

- `content-viewer` — read-only across the tenant
- `content-author` — create/edit templates, themes, stencils, reference data (attributes, code lists, fonts)
- `document-generator` — generate documents
- `content-publisher` — publish/archive template and stencil versions
- `tenant-administrator` — tenant settings, users/API keys, catalog management, diagnostics, backups, and destructive restore (does **not** include publish)

**Platform roles**:

- `tenant-manager` — create and manage tenants across the platform
- `platform-observer` — cross-tenant read-only access (diagnostics/logs/status)

### Permissions

Tenant roles map to fine-grained permissions in application code (not in Keycloak). See [`authorization.md`](authorization.md) for the full permission catalog.

| Role                   | Permissions                                                                                                     |
| ---------------------- | --------------------------------------------------------------------------------------------------------------- |
| `content-viewer`       | `TEMPLATE_VIEW`, `DOCUMENT_VIEW`, `THEME_VIEW`, `STENCIL_VIEW`, `REFERENCE_VIEW`, `CATALOG_VIEW`, `BACKUP_VIEW` |
| `content-author`       | `TEMPLATE_EDIT`, `THEME_EDIT`, `STENCIL_EDIT`, `REFERENCE_EDIT`                                                 |
| `document-generator`   | `DOCUMENT_GENERATE`                                                                                             |
| `content-publisher`    | `TEMPLATE_PUBLISH`, `STENCIL_PUBLISH`                                                                           |
| `tenant-administrator` | `TENANT_SETTINGS`, `TENANT_USERS`, `CATALOG_MANAGE`, `BACKUP_CREATE`, `DIAGNOSTICS_VIEW`, `TENANT_RESTORE`      |

Roles are **composable** — a user's effective permissions are the union of all their roles. For example, a user with `content-viewer` + `content-author` can view and edit templates and themes. Note that publishing is its own role (`content-publisher`), separate from administration.

### Migrating from the legacy role names (one-time IdP runbook)

The tenant role vocabulary was renamed from the original `reader` / `editor` / `generator` /
`manager` to the descriptive `content-viewer` / `content-author` / `document-generator` /
`content-publisher` / `tenant-administrator` (and `content-publisher` is **new** — publishing
was split out of the old `manager`). The parser recognises **only the new names**; it does not
alias the old ones.

> **This is a breaking change for any IdP still using the old group/role names.** A user whose
> groups don't parse authenticates successfully but resolves to **zero memberships** — i.e. they
> are logged in but locked out of everything (every action 403s). There is no DB migration that
> can fix this: the role labels live in your IdP, so the rename must be applied there. Do this
> **before or together with** deploying the renamed build.

Mapping (old → new):

| Old wire name               | New wire name                  | Notes                                                                                   |
| --------------------------- | ------------------------------ | --------------------------------------------------------------------------------------- |
| `reader`                    | `content-viewer`               |                                                                                         |
| `editor`                    | `content-author`               |                                                                                         |
| `generator`                 | `document-generator`           |                                                                                         |
| `manager`                   | `tenant-administrator`         | no longer grants publish (see below)                                                    |
| —                           | `content-publisher`            | **new** — grant to users who publish/archive versions (previously implied by `manager`) |
| `tenant-manager` (platform) | `tenant-manager`               | unchanged                                                                               |
| —                           | `platform-observer` (platform) | **new**, optional — cross-tenant read-only                                              |

**Hierarchical groups (Keycloak):** for every tenant, rename the leaf groups under
`/epistola/tenants/{tenant}/` and `/epistola/global/` per the table; users keep their group
membership across a rename, so no per-user reassignment is needed for the renamed roles. Then,
for anyone who needs to publish, add them to the new `content-publisher` group (the old `manager`
no longer implies it). If `keycloakAdmin.ensureGroups` is on, the new empty groups are also
created automatically on startup — but the app never renames or deletes your existing groups, so
the rename itself is a manual (or scripted) admin step.

**Flat-claim IdPs (`ept_`/`epg_`/`eps_`):** rename the realm-role / claim values the same way
(e.g. `ept_acme_reader` → `ept_acme_content-viewer`), and add a `content-publisher` value where
publishing is needed.

**Verify:** sign in as a migrated user and open `/profile` — it lists the resolved tenant
memberships, global roles and platform roles, so you can confirm the new names parsed before
rolling the rename out widely.

**API keys are unaffected** — their scope is stored in the suite's own database (already on the
new names) and is preserved across the upgrade; only IdP-sourced human logins need this runbook.

### How Parsing Works

The JWT `groups` claim contains full paths (e.g., `/epistola/tenants/demo/content-viewer`). The parser splits on `/` and routes based on the category segment:

- `/epistola/tenants/{tenant}/{role}` → per-tenant role
- `/epistola/global/{role}` → global tenant role
- `/epistola/platform/{role}` → platform role
- Anything else → ignored

This is unambiguous because the path structure encodes the category explicitly. Tenant keys cannot use the reserved names `tenants`, `global`, or `platform`.

### Global Roles

A global role like `/epistola/global/content-viewer` grants read access to **all** tenants. Global roles are merged with per-tenant roles. Example:

```
User groups: ["/epistola/tenants/acme-corp/content-author", "/epistola/global/content-viewer"]

Effective roles:
  acme-corp: {CONTENT_VIEWER, CONTENT_AUTHOR}   (per-tenant author + global viewer)
  any-tenant: {CONTENT_VIEWER}                  (from global)
```

## Flat Roles (for IdPs without groups)

When an IdP cannot emit hierarchical group paths, Epistola accepts a **flat string-array
claim** (default name `roles`, configurable via `epistola.auth.flat-roles.claim-name`).
Each entry uses one of three prefixes:

| Prefix | Pattern                  | Example                        | Meaning                                     |
| ------ | ------------------------ | ------------------------------ | ------------------------------------------- |
| `epg_` | `epg_<role>`             | `epg_content-viewer`           | Global tenant role (applies to all tenants) |
| `ept_` | `ept_<tenantKey>_<role>` | `ept_acme-corp_content-author` | Per-tenant role                             |
| `eps_` | `eps_<platformRole>`     | `eps_tenant_manager`           | Platform role                               |

Role names match the kebab-case vocabulary: `content-viewer`, `content-author`,
`document-generator`, `content-publisher`, `tenant-administrator` (tenant roles) and
`tenant-manager` / `platform-observer` (platform roles — `_` is normalised to `-` for lookup,
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

Epistola's memberships can be carried into the JWT three ways — pick **one** (or combine
them; the parsers read every shape and take the **union**, which is handy during a
migration):

- **Client roles → flat `roles` claim** — **recommended**. Keeps the Epistola authorization
  model scoped to the `epistola-suite` client instead of polluting the shared realm-role
  namespace or modelling a group tree. Step-by-step below.
- **Realm roles → flat `roles` claim** — identical wire format, but the roles live in the
  realm namespace. This is the shape the app **auto-provisions**
  ([Automatic Client Mapper Provisioning](#automatic-client-mapper-provisioning)).
- **Hierarchical groups → `groups` claim** — a navigable group tree; see
  [Group Membership Mapper](#group-membership-mapper-groups-path) below.

All three resolve to the same effective memberships.

### 1. Create the client

Create an OpenID Connect client named `epistola-suite`:

- Client authentication: ON (confidential)
- Standard flow: ON
- Service accounts: ON (needed for tenant group provisioning)
- Valid redirect URIs: your app URL (e.g., `http://localhost:4000/*`)

### Recommended: client roles (step by step)

Here the `epistola-suite` client owns a set of **client roles** whose names encode the
Epistola membership, and one mapper copies them into the flat `roles` claim.

#### 2a. Define the client roles

Go to **Clients → `epistola-suite` → Roles → Create role** and add one role per membership
you need, naming each with Epistola's flat-role encoding (the same `ept_*` / `epg_*` /
`eps_*` vocabulary as [Flat Roles](#flat-roles-for-idps-without-groups)):

| Role name (in Keycloak)              | Grants                                       |
| ------------------------------------ | -------------------------------------------- |
| `ept_acme-corp_content-viewer`       | `content-viewer` on tenant `acme-corp`       |
| `ept_acme-corp_tenant-administrator` | `tenant-administrator` on tenant `acme-corp` |
| `epg_content-viewer`                 | `content-viewer` on **all** tenants (global) |
| `eps_tenant-manager`                 | platform role `tenant-manager`               |

> **The encoding is mandatory.** A client role named `admin` or `viewer` is **silently
> ignored** — only names matching a known prefix _and_ a known role resolve, so a
> misconfigured user logs in successfully but has zero access. Tenant keys follow the slug
> rules (lowercase letters/digits/hyphens, never underscores) and `_` is the segment
> separator, hence `ept_<tenantKey>_<role>`. See the
> [Flat Roles](#flat-roles-for-idps-without-groups) table for the full prefix/role
> vocabulary.

For a multi-tenant deployment you'll have one `ept_<tenant>_<role>` client role per
tenant/role pair; use `epg_<role>` to grant a role across **all** tenants without repeating
it per tenant.

#### 2b. Assign client roles to users (or groups)

For each user: **Users → _user_ → Role mapping → Assign role → Filter by clients →** select
the `epistola-suite` roles. To manage at scale, assign the client roles to a plain Keycloak
**group** and add users to that group instead — the assignment can be indirect.

#### 2c. Add the client-role protocol mapper

Add a protocol mapper to the `epistola-suite` client (**Clients → `epistola-suite` → Client
scopes → `epistola-suite-dedicated` → Add mapper → By configuration → User Client Role**):

| Setting             | Value                                                      |
| ------------------- | ---------------------------------------------------------- |
| Name                | `epistola-client-roles`                                    |
| Mapper type         | `User Client Role` (`oidc-usermodel-client-role-mapper`)   |
| Client ID           | `epistola-suite`                                           |
| Client Role prefix  | _(leave blank)_                                            |
| Token Claim Name    | `roles` (must equal `epistola.auth.flat-roles.claim-name`) |
| Claim JSON Type     | `String`                                                   |
| Multivalued         | **ON**                                                     |
| Add to ID token     | ON                                                         |
| Add to access token | ON                                                         |
| Add to userinfo     | ON                                                         |

**Why each field matters:**

- **Client Role prefix must be blank** — Keycloak would otherwise prepend it to every value
  and break the `ept_*`/`epg_*`/`eps_*` encoding.
- **Token Claim Name = `roles`** — this is the claim Epistola's `parseFlatRoles()` reads.
  Change it only if you also set `epistola.auth.flat-roles.claim-name` to match.
- **Multivalued ON** — emits a JSON array; the parser expects a string array.
- **Enable all three token destinations** — the REST API path reads the **access token**
  while the browser OAuth2 login path reads the **ID token / userinfo**; omitting one breaks
  that surface.

> **Coexistence with auto-provisioning.** If the app runs with mapper auto-provisioning
> (`ensureGroups: true`) it also ensures a realm-role mapper named `epistola-realm-roles`
> that writes the **same** `roles` claim. Multiple multivalued mappers targeting one claim
> are merged into a single array, so the two coexist fine — the realm-role mapper simply
> contributes nothing when you assign no realm roles. The app will not touch your
> differently-named `epistola-client-roles` mapper (it logs a one-line WARN that another
> mapper writes `roles`, then leaves it alone).

#### 2d. Verify

1. Sign in and decode the issued token (e.g. <https://jwt.io>). Confirm a top-level
   **`roles`** array containing your encoded values — e.g.
   `["ept_acme-corp_content-viewer", "eps_tenant-manager"]`. If the client roles appear only
   under `resource_access.epistola-suite.roles`, the mapper isn't writing the flat claim —
   recheck step 2c.
2. Open **`/profile`** in the app — it lists the resolved tenant memberships, global roles
   and platform roles. Empty here despite roles in the token means the **names** don't match
   the encoding (step 2a).

### Group Membership Mapper (groups path)

Prefer hierarchical groups instead of (or alongside) client roles? This can be configured
manually via the admin UI (steps below), or auto-provisioned by the app — see
[Automatic Client Mapper Provisioning](#automatic-client-mapper-provisioning) for the
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

**Important:** `Full group path` must be ON so the JWT contains full paths like `/epistola/tenants/demo/content-viewer`.

#### Create groups

Create the hierarchical group structure:

1. Create root group `epistola`
2. Under `epistola`, create `tenants`, `global`, and `platform`
3. Under `tenants`, create a sub-group for each tenant (e.g., `demo`)
4. Under each tenant, create role groups: `content-viewer`, `content-author`, `document-generator`, `content-publisher`, `tenant-administrator`
5. Under `global`, create the same five role groups
6. Under `platform`, create: `tenant-manager`, `platform-observer`

Alternatively, configure the chart with `oidc.enabled: true` and `keycloakAdmin.ensureGroups: true` to have the app create the base structure automatically on startup (see below).

#### Assign users to groups

Assign users to the leaf groups (e.g., `/epistola/tenants/demo/content-viewer`). Assigning to intermediate groups (e.g., `/epistola/tenants/demo`) has no effect on authorization.

## Automatic Tenant Provisioning

When the `keycloakAdmin` client secret is configured, Epistola automatically creates hierarchical Keycloak groups when a new tenant is created via the UI. The four role groups are created under `/epistola/tenants/{key}/`:

- `/epistola/tenants/{key}/content-viewer`
- `/epistola/tenants/{key}/content-author`
- `/epistola/tenants/{key}/document-generator`
- `/epistola/tenants/{key}/tenant-administrator`

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
manually (see [Group Membership Mapper](#group-membership-mapper-groups-path) above) or
grant the role.

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

| Username         | Password    | Roles on `demo` tenant                   | Platform roles                      |
| ---------------- | ----------- | ---------------------------------------- | ----------------------------------- |
| `admin@demo`     | `admin`     | All roles (incl. publisher)              | `tenant-manager` + all global roles |
| `reader@demo`    | `reader`    | Viewer                                   | —                                   |
| `editor@demo`    | `editor`    | Viewer, Author                           | —                                   |
| `generator@demo` | `generator` | Viewer, Generator                        | —                                   |
| `manager@demo`   | `manager`   | Viewer, Author, Generator, Administrator | —                                   |

Self-registration is enabled — new users can register with email + password. In demo mode (`epistola.demo.enabled=true`), users without group memberships are automatically assigned to a tenant derived from their email domain (e.g., `user@acme.io` → tenant `acme-io` with all roles).

### OIDC Logout

Logging out of Epistola also ends the Keycloak SSO session (via OIDC RP-Initiated Logout), so the next login shows the Keycloak login form instead of auto-authenticating.

## JWT Claim Example

After login against an auto-provisioned Keycloak, the JWT contains both shapes for the
same effective memberships:

```json
{
  "groups": [
    "/epistola/tenants/demo/content-viewer",
    "/epistola/tenants/demo/content-author",
    "/epistola/tenants/demo/document-generator",
    "/epistola/tenants/demo/tenant-administrator",
    "/epistola/platform/tenant-manager"
  ],
  "roles": [
    "ept_demo_content-viewer",
    "ept_demo_content-author",
    "ept_demo_document-generator",
    "ept_demo_tenant-administrator",
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
   - Static group assignment, e.g. all brokered users land in `/epistola/global/content-viewer`.
   - Conditional / attribute-based assignment, e.g. users in the IDP's "Tenant Admins"
     group are mapped into `/epistola/platform/tenant-manager`.
3. Users authenticate via their corporate IDP. Keycloak provisions a local shadow user on
   first login and issues a token that conforms to the contract documented above.

### Token contract (read this first)

For any IDP integration, the token Epistola Suite receives must contain `sub` and
`email`, plus **at least one** of the following claim shapes:

| Claim                                  | Type            | Example values                                                                                                                                                                                           |
| -------------------------------------- | --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `groups`                               | array of string | `["/epistola/tenants/acme-corp/content-viewer", "/epistola/platform/tenant-manager"]` — paths must start with `/epistola/` and follow the conventions in [Group Path Convention](#group-path-convention) |
| `roles` (or the configured equivalent) | array of string | `["ept_acme-corp_content-viewer", "eps_tenant_manager"]` — see [Flat Roles](#flat-roles-for-idps-without-groups)                                                                                         |

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
