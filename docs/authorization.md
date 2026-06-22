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

| Role                   | Capability                                                                                      |
| ---------------------- | ----------------------------------------------------------------------------------------------- |
| `CONTENT_VIEWER`       | Read-only across the tenant.                                                                    |
| `CONTENT_AUTHOR`       | Create/edit templates, themes, stencils, and reference data.                                    |
| `DOCUMENT_GENERATOR`   | Generate documents.                                                                             |
| `CONTENT_PUBLISHER`    | Publish/archive template & stencil versions. **Separate from admin.**                           |
| `TENANT_ADMINISTRATOR` | Tenant settings, users/API keys, catalogs, diagnostics, backups, restore. **Does not publish.** |

The split of `CONTENT_PUBLISHER` from `TENANT_ADMINISTRATOR` is deliberate: approving content
is a different duty from administering a tenant. An admin who also approves content holds both.

## Platform roles

`security/PlatformRole.kt`. Cross-tenant, never persisted (token/config only).

| Role                | Capability                                                            |
| ------------------- | --------------------------------------------------------------------- |
| `TENANT_MANAGER`    | Create and manage tenants across the platform.                        |
| `PLATFORM_OBSERVER` | Cross-tenant **read-only** (diagnostics/logs/status) for ops/support. |

## Permission catalog

`security/Permission.kt`. Role → permission mapping is `TenantRole.permissions()`.

| Permission group                                                      | Permissions                                            | Default role                |
| --------------------------------------------------------------------- | ------------------------------------------------------ | --------------------------- |
| Templates                                                             | `TEMPLATE_VIEW` / `TEMPLATE_EDIT` / `TEMPLATE_PUBLISH` | viewer / author / publisher |
| Stencils                                                              | `STENCIL_VIEW` / `STENCIL_EDIT` / `STENCIL_PUBLISH`    | viewer / author / publisher |
| Themes                                                                | `THEME_VIEW` / `THEME_EDIT`                            | viewer / author             |
| Documents                                                             | `DOCUMENT_VIEW` / `DOCUMENT_GENERATE`                  | viewer / generator          |
| Reference data (attributes, code lists, fonts)                        | `REFERENCE_VIEW` / `REFERENCE_EDIT`                    | viewer / author             |
| Catalogs                                                              | `CATALOG_VIEW` / `CATALOG_MANAGE`                      | viewer / administrator      |
| Diagnostics (logs, status)                                            | `DIAGNOSTICS_VIEW`                                     | administrator               |
| Backups                                                               | `BACKUP_VIEW` / `BACKUP_CREATE`                        | viewer / administrator      |
| **Destructive** (restore backup/snapshot, purge catalogs)             | `TENANT_RESTORE`                                       | administrator               |
| Tenant config (feature toggles, defaults, environments, entitlements) | `TENANT_SETTINGS`                                      | administrator               |
| Users & API keys                                                      | `TENANT_USERS`                                         | administrator               |

`TENANT_RESTORE` is named to signal danger and is held only by `TENANT_ADMINISTRATOR`. It is its
own permission so it can be withheld or audited independently — notably, API keys never receive
it by default.

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

REST controllers, MCP tools, and most UI handlers are thin and flow through the mediator, so they
inherit this single check. A few UI handlers add an explicit `requirePermission(...)` when their
backing query is only `RequiresAuthentication` and the page needs tighter gating. UI **visibility**
(nav/footer via `UiRequestContext.hasPermission`) is defense-in-depth, not the enforcement boundary.

## What a denied API caller receives

Each failed check maps to an RFC 7807 `application/problem+json` body, **HTTP 403**
(`ApiExceptionHandler` + `ApiProblemTypes`). A missing/invalid/expired key is **401**
`unauthorized`, before any permission check.

| Failure                           | Exception                       | `type` slug              | Names                            |
| --------------------------------- | ------------------------------- | ------------------------ | -------------------------------- |
| Lacks the fine-grained permission | `PermissionDeniedException`     | `permission-denied`      | `requiredPermission`, `tenantId` |
| No access to the tenant           | `TenantAccessDeniedException`   | `access-denied`          | `tenantId`                       |
| Lacks the platform role           | `PlatformAccessDeniedException` | `platform-access-denied` | `requiredRole`                   |

The missing permission is named in both the `detail` string and a machine-readable extension
(kebab-case, e.g. `template-edit`), so an integrator can resolve a 403 without server logs:

```json
{
  "type": "https://epistola.app/errors/permission-denied",
  "title": "Permission Denied",
  "status": 403,
  "detail": "Missing required permission: template-edit",
  "requiredPermission": "template-edit",
  "tenantId": "acme",
  "instance": "/api/tenants/acme/catalogs/default/templates"
}
```

## API keys are least-privilege

`api/security/ApiKeyAuthenticationFilter.kt` builds the request principal from the **roles stored
on the key** (`api_keys.roles`), not the full role set. A key is created via the UI
(`/tenants/{tenant}/api-keys`) where the operator picks the scope — `CONTENT_VIEWER` is pre-checked
so the narrowest scope is the default. So a read-only or generate-only integration key is expressible,
and MCP sessions inherit the key's scope automatically.

Administration (`TENANT_ADMINISTRATOR`, which carries `TENANT_RESTORE`) is offered but never
pre-selected — keys stay least-privilege unless deliberately escalated. The `CreateApiKey` command
requires a non-empty scope; its Kotlin default of "all roles" exists only as a convenience for
tests/fixtures (and the deterministic demo key), never for keys created through the UI.

## Mapping IdP roles in

The IdP wire vocabulary is kebab-case and maps 1:1 to the enum constants
(`GroupMembershipParser` / `FlatRoleParser`): `content-viewer`, `content-author`,
`document-generator`, `content-publisher`, `tenant-administrator`, plus platform `tenant-manager`
and `platform-observer`. Both hierarchical Keycloak groups (`/epistola/tenants/{t}/{role}`) and
flat prefixed claims (`ept_{tenant}_{role}`, `epg_{role}`, `eps_{platformRole}`) are supported.
See [`keycloak-setup.md`](keycloak-setup.md).

Only the new kebab names are recognised — the legacy `reader`/`editor`/`generator`/`manager`
names are not aliased, so an IdP still emitting them resolves a user to **zero memberships**
(logged in but locked out). Renaming them is a one-time IdP-side step with no DB migration; see
the runbook in [`keycloak-setup.md`](keycloak-setup.md#migrating-from-the-legacy-role-names-one-time-idp-runbook).

## Changing the model

- **Add a permission:** add to `Permission.kt`, grant it in `TenantRole.permissions()`, and tag the
  relevant commands/queries with `RequiresPermission`.
- **Add a tenant role:** add to `TenantRole.kt`, map its permissions, add the kebab wire name to
  `KNOWN_TENANT_ROLES`, and update the IdP group provisioning.
- **Add a platform role:** add to `PlatformRole.kt` and `KNOWN_PLATFORM_ROLES`; the mediator's
  `requirePlatformRole` handles it generically.
- Update `AuthorizationCoverageTest`'s package list if you add a new domain package, and the
  role-matrix assertions in `PermissionTest`.
