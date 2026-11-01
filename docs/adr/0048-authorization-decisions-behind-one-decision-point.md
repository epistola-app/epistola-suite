# ADR 0048: Authorization decisions behind one decision point

- **Status:** Proposed
- **Date:** 2026-11-01
- **Deciders:** Epistola team
- **Tags:** security, authorization, mediator, roles, permissions, pbac, contract, audit
- **Related:** [ADR 0004](0004-rfc7807-problem-details.md) (problem details), [ADR 0043](0043-tenant-search.md)
  (search filtered by what the reader may see), [`docs/authorization.md`](../authorization.md)

## Context

Authorization is enforced in one place: the mediator. Every command and query implements an
`Authorized` marker, `SpringMediator.enforceAuthorization()` checks it before the handler runs, and
`AuthorizationCoverageTest` fails the build when a message declares nothing. The identity provider
supplies coarse roles; roles map to permissions in Kotlin (`TenantRole.permissions()`). That seam is
sound and stays.

What the check can see is the problem. A `RequiresPermission` message offers a tenant and a
permission, and the check answers from the principal's roles in that tenant. Nothing about the thing
being acted on reaches it. Three pressures meet there.

**Organisations will bring their own policy.** Government customers run, or are moving to,
policy-based and attribute-based access control (PBAC/ABAC): a central engine decides from
attributes of the subject, the action, the resource and the circumstances. Requests we cannot express
today:

- deploy to a test environment, not to production (`Environment.production` exists, the check never
  sees it);
- author only the catalogs of one team;
- let an integration generate from one template only;
- treat an assistant working over MCP differently from a REST integration with the same key — today
  "MCP never generates documents" holds only because no MCP tool does.

**New features stretched the vocabulary.** Each feature reached for the nearest existing permission:

| Feature                                                                 | Gated by             | Problem                                                                           |
| ----------------------------------------------------------------------- | -------------------- | --------------------------------------------------------------------------------- |
| Feedback: create, comment, upload, change status (synced to the hub)    | `DOCUMENT_VIEW`      | Unrelated to documents; "own or administrator" lives in a handler as a role check |
| Load tests: start and cancel                                            | `DOCUMENT_GENERATE`  | Any generating integration can start one                                          |
| Quality findings: ignore and resolve                                    | `TEMPLATE_EDIT`      | Ignoring a finding that gates a release is not authoring                          |
| Exchange connection, quality providers, defaults, environments, toggles | `TENANT_SETTINGS`    | One permission for settings that send tenant data out and settings that do not    |
| API keys                                                                | `TENANT_USERS`       | Users come from the identity provider; it governs only keys                       |
| Forgetting a cluster node (installation-wide)                           | `DIAGNOSTICS_MANAGE` | A tenant permission over installation state                                       |

**The edges of the model have gaps.**

- `CONTENT_VIEWER` grants `DOCUMENT_VIEW`, so whoever may review templates may read every generated
  document, with the personal data in it. Reviewing content and reading output are different duties.
- `PLATFORM_OBSERVER` is parsed from the identity provider and documented, but nothing checks it: the
  role grants nothing. `TENANT_MANAGER` covers tenant lifecycle, namespaces, the site banner and the
  installation's data-sharing overview.
- `SystemInternal` means two things: real system work (login, schedulers, sync), and reads every
  member of a tenant may make (`ListCatalogEditability`, `ResolveFeatureToggles`,
  `GetExchangeSettings`, …). The second kind skips the tenant-access check with the first, because no
  marker says "any member of this tenant".
- 80 UI handler checks (`requirePermission`) duplicate or tighten what the command already requires,
  and they drift: creating an attribute or a code list needs `TENANT_SETTINGS` in the UI and
  `REFERENCE_EDIT` over REST.
- A refused attempt is not audited. The check runs before the dispatch that notifies the audit
  listener, so the audit log records what happened and never what was refused.

**The contract describes the vocabulary.** Every operation in `epistola-contract` declares
`x-required-permissions`, and its `docs/auth.md` publishes the role-to-permission table. The 403 body
names a `requiredPermission`, a field the suite added and the contract does not specify. The two have
already drifted: `feature/v2` still lists `BACKUP_VIEW`, `BACKUP_CREATE` and `TENANT_RESTORE`, which
the 2.0 suite withdrew, lacks `DIAGNOSTICS_MANAGE`, and nothing checks either side against the other.

## Decision

### 1. One decision point, asked one question

The mediator builds an **authorization request** from the message and asks an
`AuthorizationDecisionPoint` for a decision:

```kotlin
data class AuthorizationRequest(
    val subject: Subject,      // user key, kind (USER, API_KEY), tenant roles, platform roles
    val action: Permission,    // the action vocabulary stays the permission enum
    val resource: ResourceRef, // tenant, type, id when there is one, resolved attributes
    val context: RequestContext, // channel (WEB, REST, MCP), request id, time
)

data class AuthorizationDecision(val allowed: Boolean, val reason: DenialReason?)

interface AuthorizationDecisionPoint {
    fun decide(request: AuthorizationRequest): AuthorizationDecision
    fun scope(subject: Subject, action: Permission, type: ResourceType, context: RequestContext): ResourceScope
}
```

The shape is that of the OpenID AuthZEN [Authorization API 1.0](https://openid.net/specs/authorization-api-1_0.html)
(final, January 2026): subject, action, resource and context in, a boolean decision with an optional
context (the reason) out. An external engine can then be reached through a standard request rather
than an Epistola-specific one.

- **The default is today's table.** `RoleBasedDecisionPoint` answers from `TenantRole.permissions()`
  and platform roles, and ignores resource attributes. Behaviour is identical; the existing
  authorization tests prove it before anything else changes.
- **Messages name their resource; the mediator resolves its attributes.** A `RequiresPermission`
  message may declare a `ResourceRef` (default: the tenant alone). Attributes such as
  `environment.production` or a resource's catalog are looked up by the mediator, never taken from the
  caller, and cached for the dispatch.
- **An external engine plugs in as another implementation**, in its own module, selected by
  configuration. It receives the roles as subject attributes, so a policy can start from RBAC and
  narrow or widen it. It fails closed: an error, a timeout or an unknown answer is a denial.
- **`SystemInternal` never reaches the decision point.** System work is not a policy question.

### 2. Lists are filtered in the database

A list cannot ask once per row: that is in-memory filtering, and it breaks the rule that sorting,
limiting and counting happen in SQL. A list query asks `scope()` once and binds the answer into its
statement. `ResourceScope` is `All`, `None` or `Only(keys)`, over the dimensions we scope by:
catalogs and environments to begin with. The default answers `All` or `None`. AuthZEN's Resource
Search API ("which resources of this type may this subject act on") has the same shape, so an external
engine answers through it.

### 3. A marker for any member, and checks in the messages

- **`RequiresTenantMembership`** — any role in the tenant, tenant access checked. The user-facing reads
  that are `SystemInternal` today move to it. A guard test then refuses a `SystemInternal` message
  dispatched from a web handler, REST controller or MCP tool, outside an allow-list for login and
  authentication filters.
- **The UI asks the decision point**, not the role table: `UiRequestContext.hasPermission` and the
  handler checks call `decide()` for the action and resource of the command the page leads to. A
  policy that refuses deploying to production then also hides the button. A handler check that names a
  different permission than its command is a defect; the attribute and code-list forms are corrected
  to `REFERENCE_EDIT`.

### 4. Every refusal is audited

The audit listener receives refused dispatches as well: subject key, action, resource reference,
reason, channel. No personal data, like the rest of the audit log. Allowed commands are audited as
today.

### 5. The 2.0 vocabulary

These change what a role grants or what a 403 names, so they ship in 2.0 only:

| Change                                                                                                                                              | Why                                                                                |
| --------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| `DOCUMENT_VIEW` leaves `CONTENT_VIEWER`; `DOCUMENT_GENERATOR` grants it                                                                             | Reviewing content is not reading output; integrations fetch what they generate     |
| New tenant role `DOCUMENT_READER` (`document-reader`): `DOCUMENT_VIEW`                                                                              | People who read generated documents without generating                             |
| `FEEDBACK_SUBMIT` (viewer) and `FEEDBACK_MANAGE` (administrator); "own" is a resource attribute                                                     | Feedback stops borrowing `DOCUMENT_VIEW`; the rule leaves the handler              |
| Load tests: `DIAGNOSTICS_MANAGE` to start and cancel, `DIAGNOSTICS_VIEW` to read                                                                    | An operational tool, not document generation                                       |
| `INTEGRATIONS_MANAGE` (administrator) split from `TENANT_SETTINGS`: Exchange connection, quality and AI providers                                   | Settings that send tenant data out are a separate decision, like `CATALOG_PUBLISH` |
| `TENANT_USERS` becomes `CREDENTIALS_MANAGE`: API keys and, later, consumers                                                                         | Says what it governs                                                               |
| Platform: new `INSTALLATION_ADMINISTRATOR` for cluster maintenance, the site banner and data sharing; `TENANT_MANAGER` keeps tenants and namespaces | Installation state gets an installation role                                       |
| `PLATFORM_OBSERVER` grants `DIAGNOSTICS_VIEW` and `AUDIT_VIEW` in every tenant, read-only                                                           | The role finally does what it is documented to do                                  |

Dispositions on quality findings follow ADR 0046 (quality profiles and rules, proposed alongside the
quality checks) once its rules gate releases: ignoring a gating finding needs `QUALITY_EDIT`, the publisher's permission, not
`TEMPLATE_EDIT`.

### 6. The contract carries the vocabulary, and a test holds the two together

Changes to `epistola-contract`, on `feature/v2`:

- `x-required-permissions` updated for every operation the vocabulary above touches, and the role
  table in `docs/auth.md` rewritten from the suite's (`BACKUP_*` and `TENANT_RESTORE` removed,
  `DIAGNOSTICS_MANAGE` and the new permissions added).
- The 403 responses specified: `permission-denied` with `requiredPermission` and `tenantId`, as the
  suite already sends; `platform-access-denied` with `requiredRole`; and a new `policy-denied` with
  `action`, `reason` and an optional `policy`. A decision by policy has no single missing permission.
  The role-based default keeps answering `permission-denied`.
- The permission vocabulary published in the artifact as data (every permission, and which role
  grants it), so a suite test (`PermissionContractAlignmentTest`) compares it with `Permission.entries`
  and `TenantRole.permissions()`.
- `ConsumerPermissions` (`templates: read|write`, scopes `epistola:{resource}:{level}`) is the
  experimental consumer vocabulary. It stays experimental until consumers are implemented. It is then
  mapped onto suite permissions, not given a second meaning.

## Options considered

- **More roles, no decision point.** Each new need becomes a role. The roles multiply and still
  cannot say "this environment" or "this catalog". Rejected.
- **Embed one engine now** (OpenFGA, Cedar, OPA as a library). It decides the engine for every
  installation and adds an operational dependency to the default deployment. The decision point keeps
  that choice open; an engine arrives as an implementation when a customer needs it. Rejected for now.
- **Annotations on controllers and handlers** (`@PreAuthorize`). Spreads enforcement over three
  surfaces again: exactly the drift the mediator seam removed. Rejected.
- **Chosen:** the decision point behind the existing seam, role-based by default, AuthZEN-shaped,
  with lists filtered in SQL.

## Consequences

- **Resource attributes cost lookups.** Resolving `environment.production` or a resource's catalog is
  a query per dispatch, cached for the dispatch. The default decision point needs none, so the cost
  arrives with the first attribute a policy reads.
- **Every list query that is scoped gains a predicate.** Catalog and environment lists first;
  each new scoped dimension is work in each list that shows it.
- **An external engine adds latency and a failure mode.** Calls are batched per page through
  AuthZEN's evaluations request where a page needs several decisions, and a failure denies.
- **The audit log grows by the refusals.** Bounded by what callers attempt; no personal data.
- **2.0 breaks the role vocabulary deliberately.** Installations re-check which roles their people
  hold, mainly who needs `document-reader`. The upgrade notes list every change in the table above.

## Delivery

1. **Additive, no contract change:** the decision point with the role-based default, refusals
   audited, `RequiresTenantMembership` and the `SystemInternal` guard, the UI asking the decision
   point, the attribute and code-list forms corrected. Can land in a 1.x minor as well as 2.0.
2. **2.0:** the vocabulary of section 5, the contract changes of section 6, the alignment test.
3. **When a customer needs it:** resource attributes (environment first), scoped lists, and an
   AuthZEN client as the first external implementation.
