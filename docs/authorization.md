# Authorization

This document describes Epistola Suite's **authorization** model — roles, permissions, how
they are enforced, and how API keys are scoped. For **authentication** (how a principal is
established: form login, OAuth2/OIDC, API key) see [`auth.md`](auth.md); for the IdP group/claim
conventions that feed roles in, see [`keycloak-setup.md`](keycloak-setup.md).

## The model at a glance

Four layers, decoupled so the IdP only ever deals in coarse role labels:

1. **IdP roles** — Keycloak groups / flat claims emit coarse role names (`content-viewer`, …).
2. **`TenantRole` / `PlatformRole`** — the roles a principal holds (parsed from the IdP).
3. **`Permission`** — fine-grained, application-defined capabilities. Roles map to permissions
   **in Kotlin** (`Permission.kt`), never in the IdP.
4. **Enforcement** — every command/query declares what it needs; the mediator checks it.

Roles are **composable and non-inheriting**: a principal's effective permissions are the union
of each held role's grant. There is no implicit hierarchy — `content-author` does not include
`content-viewer`; grant both if a user needs to read and write (the IdP setup does this).

## Tenant roles

`security/TenantRole.kt`. Held per-tenant (and/or globally across all tenants).

| Role                   | Capability                                                                                                          |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------- |
| `CONTENT_VIEWER`       | Read-only content: templates, themes, stencils, reference data, catalogs. Send support requests. **Not documents.** |
| `CONTENT_AUTHOR`       | Create/edit templates, themes, stencils, and reference data.                                                        |
| `DOCUMENT_GENERATOR`   | Generate documents, and read the documents generated.                                                               |
| `DOCUMENT_READER`      | Read generated documents without generating.                                                                        |
| `CONTENT_PUBLISHER`    | Release catalogs, deploy releases, publish to Exchange, edit quality profiles. **Separate from admin.**             |
| `TENANT_ADMINISTRATOR` | Tenant settings, integrations, API keys, support requests, catalogs, diagnostics and audit. **Does not publish.**   |

The split of `CONTENT_PUBLISHER` from `TENANT_ADMINISTRATOR` is deliberate: approving content
is a different duty from administering a tenant. An admin who also approves content holds both.
Likewise reviewing content is not reading its output: generated documents carry personal data, so
`CONTENT_VIEWER` does not grant `DOCUMENT_VIEW` (ADR 0048); give `document-reader` to whoever reads
documents without generating them.

## Platform roles

`security/PlatformRole.kt`. Cross-tenant, never persisted (token/config only).

| Role                         | Capability                                                                                                                                                                             |
| ---------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `TENANT_MANAGER`             | Create and delete tenants, and grant them namespaces.                                                                                                                                  |
| `INSTALLATION_ADMINISTRATOR` | Installation state: the cluster page (Platform → Cluster) and forgetting its nodes, the site banner, what the installation shares with Epistola, and who handles new support requests. |
| `SUPPORT_DESK`               | The support desk (Platform → Support requests): every tenant's support requests, their replies and internal notes, status and priority, and passing them on. Nothing in any tenant.    |
| `PLATFORM_OBSERVER`          | `DIAGNOSTICS_VIEW` and `AUDIT_VIEW` in **every** tenant, member or not, and the cluster page. Read-only.                                                                               |

`PLATFORM_OBSERVER`'s grants are `PlatformRole.permissionsInEveryTenant()`: the mediator lets a
message needing one of them through without tenant membership, and refuses anything else in a tenant
the observer is not a member of.

The cluster page is the installation's, not a tenant's: its nodes, scheduled tasks and timers span
every tenant, so no tenant role opens it. Its reads are `ClusterInspection` messages, which pass for
the installation administrator and the platform observer (`RequiresPlatformRole.alsoAllowedPlatformRoles`).

## Permission catalog

`security/Permission.kt`. Role → permission mapping is `TenantRole.permissions()`. The contract
publishes the same vocabulary as data (`x-authorization` in the server stubs' spec), and
`PermissionContractAlignmentTest` fails the build when the two differ: a name, a grant, an
identity-provider name, or an operation requiring a permission the suite does not have.

| Permission group                                                     | Permissions                                         | Default role                       |
| -------------------------------------------------------------------- | --------------------------------------------------- | ---------------------------------- |
| Templates                                                            | `TEMPLATE_VIEW` / `TEMPLATE_EDIT`                   | viewer / author                    |
| Stencils                                                             | `STENCIL_VIEW` / `STENCIL_EDIT`                     | viewer / author                    |
| Releasing (cut a release, discard a draft)                           | `CATALOG_RELEASE`                                   | publisher                          |
| Deploying (what an environment serves)                               | `ENVIRONMENT_DEPLOY`                                | publisher                          |
| Publishing to Exchange                                               | `CATALOG_PUBLISH`                                   | publisher                          |
| Quality profiles and their bindings                                  | `QUALITY_EDIT`                                      | publisher                          |
| Themes                                                               | `THEME_VIEW` / `THEME_EDIT`                         | viewer / author                    |
| Documents                                                            | `DOCUMENT_VIEW` / `DOCUMENT_GENERATE`               | generator or reader / generator    |
| Reference data (attributes, code lists, fonts)                       | `REFERENCE_VIEW` / `REFERENCE_EDIT`                 | viewer / author                    |
| Catalogs                                                             | `CATALOG_VIEW` / `CATALOG_MANAGE`                   | viewer / administrator             |
| Diagnostics (logs, consumers, load tests)                            | `DIAGNOSTICS_VIEW` / `DIAGNOSTICS_MANAGE`           | administrator (view also observer) |
| Audit trail                                                          | `AUDIT_VIEW`                                        | administrator, observer            |
| Tenant config (feature toggles, defaults, environments)              | `TENANT_SETTINGS`                                   | administrator                      |
| Integrations that send tenant data out (Exchange, quality providers) | `INTEGRATIONS_MANAGE`                               | administrator                      |
| API keys                                                             | `CREDENTIALS_MANAGE`                                | administrator                      |
| Support requests: send and follow up / handle the tenant's           | `SUPPORT_REQUEST_SUBMIT` / `SUPPORT_REQUEST_HANDLE` | viewer / administrator             |

`INTEGRATIONS_MANAGE`, `SUPPORT_REQUEST_SUBMIT`, `SUPPORT_REQUEST_HANDLE`, `DIAGNOSTICS_*` and `AUDIT_VIEW`
gate suite features no REST operation reaches (`outsideApi` in the contract).

## Enforcement — one fail-closed seam

Authorization is enforced in **one place**: the mediator. Every `Command`/`Query` must implement
one of the `Authorized` markers in `security/Authorized.kt`, and `SpringMediator.enforceAuthorization()`
checks it before the handler runs:

| Marker                   | Meaning                                                                       |
| ------------------------ | ----------------------------------------------------------------------------- |
| `RequiresPermission`     | Requires `permission` within `tenantKey` (checks tenant access + permission). |
| `RequiresPlatformRole`   | Requires a `PlatformRole` (e.g. `TENANT_MANAGER`).                            |
| `RequiresAuthentication` | Any authenticated user (tenant access checked if also `TenantScoped`).        |
| `SystemInternal`         | Bypasses checks — login flows, background jobs, hub sync.                     |

A message that declares **nothing** is rejected at runtime (`must implement Authorized`). The
build-time `AuthorizationCoverageTest` additionally asserts every concrete command/query implements
a marker, so an omission fails the build, not just production.

### An operation is authorized as a whole

A REST operation enforces the one permission its contract declares (`x-required-permissions`), and a
caller holding only that permission is not refused anything else the operation does. Two scopes in
`security/AuthorizedOperation.kt` make that hold without leaving the mediator:

- **`authorizedOperation(tenant, permission) { … }`** wraps a REST write. It checks the permission
  once, with the usual refusals; inside, a **query** of that tenant needs no permission of its own
  (tenant access is still checked), so reading to do and answer the write does not ask an author for
  `TEMPLATE_VIEW`. A **command** is checked as always.
- **`partOfCurrentCommand(tenant) { … }`** is for a handler that builds on other commands, as opening
  a draft from a release exports the release and imports it into the draft. The commands and queries
  it dispatches in that tenant are part of the command being handled. It grants nothing unless the
  mediator is handling an authorized command of that tenant on the same thread.

`OperationPermissionContractIT` drives every REST operation that declares a permission twice: with
every role but the ones granting it (expecting `403 forbidden` naming it) and with only those roles
(expecting no refusal). An operation the contract adds fails it until it is listed there.

REST controllers, MCP tools, and most UI handlers are thin and flow through the mediator, so they
inherit this single check. A few UI handlers add an explicit `requirePermission(...)` when their
backing query is only `RequiresAuthentication` and the page needs tighter gating. UI **visibility**
(nav/footer via `UiRequestContext.hasPermission`) is defense-in-depth, not the enforcement boundary.

## What a denied API caller receives

Each failed check maps to an RFC 9457 `application/problem+json` body, **HTTP 403**
(`ApiExceptionHandler` + `ApiProblemTypes`). A missing/invalid/expired key is **401**
`unauthorized`, before any permission check.

Every refusal is the contract's one 403 type, `forbidden`. Which members are present says what was
refused (contract `ForbiddenProblemDetail`, ADR 0048):

| Failure                           | Exception                       | Members                           |
| --------------------------------- | ------------------------------- | --------------------------------- |
| Lacks the fine-grained permission | `PermissionDeniedException`     | `requiredPermission`, `tenantKey` |
| No access to the tenant           | `TenantAccessDeniedException`   | `tenantKey`                       |
| Lacks the platform role           | `PlatformAccessDeniedException` | `requiredPlatformRole`            |

A fourth member, `reason`, is reserved for a policy refusal with no single permission to grant;
the suite does not send it yet. Permissions and roles are named as the contract's
`x-required-permissions` and `x-required-platform-roles` name them (`TEMPLATE_EDIT`), in the
`detail` string and the member, so an integrator can resolve a 403 without server logs:

```json
{
  "type": "https://epistola.app/errors/forbidden",
  "title": "Forbidden",
  "status": 403,
  "detail": "Missing required permission: TEMPLATE_EDIT",
  "requiredPermission": "TEMPLATE_EDIT",
  "tenantKey": "acme",
  "instance": "/api/tenants/acme/catalogs/default/templates"
}
```

Before 2.0 the suite sent three types of its own (`permission-denied`, `access-denied`,
`platform-access-denied`) with `tenantId`, `requiredRole` and kebab-case names.

## API keys are least-privilege

`api/security/ApiKeyAuthenticationFilter.kt` builds the request principal from the **roles stored
on the key** (`api_keys.roles`), not the full role set. A key is created via the UI
(`/tenants/{tenant}/api-keys`) where the operator picks the scope — `CONTENT_VIEWER` is pre-checked
so the narrowest scope is the default. So a read-only or generate-only integration key is expressible,
and MCP sessions inherit the key's scope automatically.

Administration (`TENANT_ADMINISTRATOR`) is offered but never
pre-selected — keys stay least-privilege unless deliberately escalated. The `CreateApiKey` command
requires a non-empty scope; its Kotlin default of "all roles" exists only as a convenience for
tests/fixtures (and the deterministic demo key), never for keys created through the UI.

A key's scope never exceeds its creator's roles in that tenant: `CreateApiKey` refuses any role the
creator does not hold (tenant and global roles together), and the create dialog offers only those.
Without it, the administrator who may create keys could mint one that publishes, defeating the split
between administering a tenant and publishing its content.

## Mapping IdP roles in

The IdP wire vocabulary is kebab-case and maps 1:1 to the enum constants
(`GroupMembershipParser` / `FlatRoleParser`): `content-viewer`, `content-author`,
`document-generator`, `document-reader`, `content-publisher`, `tenant-administrator`, plus platform
`tenant-manager`, `installation-administrator` and `platform-observer`. Both hierarchical Keycloak groups (`/epistola/tenants/{t}/{role}`) and
flat prefixed claims (`ept_{tenant}_{role}`, `epg_{role}`, `eps_{platformRole}`) are supported.
See [`keycloak-setup.md`](keycloak-setup.md).

Only the new kebab names are recognised — the legacy `reader`/`editor`/`generator`/`manager`
names are not aliased, so an IdP still emitting them resolves a user to **zero memberships**
(logged in but locked out). Renaming them is a one-time IdP-side step with no DB migration; see
the runbook in [`keycloak-setup.md`](keycloak-setup.md#migrating-from-the-legacy-role-names-one-time-idp-runbook).

## Changing the model

- **The contract comes first.** The vocabulary is the contract's (`x-authorization`): a new or
  changed permission or role is a contract change, released, then followed here;
  `PermissionContractAlignmentTest` refuses the suite going ahead alone.
- **Add a permission:** add to `Permission.kt`, grant it in `TenantRole.permissions()`, and tag the
  relevant commands/queries with `RequiresPermission`.
- **Add a tenant role:** add to `TenantRole.kt`, map its permissions, add the kebab wire name to
  `KNOWN_TENANT_ROLES`, and add the matching group/role at your IdP (see
  [keycloak-setup.md](keycloak-setup.md) / [authentik-setup.md](authentik-setup.md)).
- **Add a platform role:** add to `PlatformRole.kt` and `KNOWN_PLATFORM_ROLES`; the mediator's
  `requirePlatformRole` handles it generically.
- Update `AuthorizationCoverageTest`'s package list if you add a new domain package, and the
  role-matrix assertions in `PermissionTest`.
