# Consumer Management API — design

## Status

**Design / pre-implementation.** Tracked across three repos via separate
GitHub issues; this document is the canonical design that those issues
reference. Not part of v0.3.

## Why this exists

The contract spec in `epistola-contract` already defines a complete
Consumer Management surface (`spec/paths/consumers.yaml`,
`spec/components/schemas/consumers.yaml`):

- `POST /tenants/{tid}/consumers/register` (self-service, pending)
- `GET /tenants/{tid}/consumers` (paginated list — manager role)
- `GET /tenants/{tid}/consumers/{cid}` / `PATCH` / `DELETE`
- `POST /tenants/{tid}/consumers/{cid}/approve` / `/reject`
- `PUT /tenants/{tid}/consumers/{cid}/public-key`

with rich DTOs:

- `ConsumerDto` — id (slug), name, status (pending/active/rejected/expired/inactive),
  authMethod (oauth/self-signed-jwt), requested + granted permissions,
  publicKey, expiresAt, **`nodes: ConsumerNodeDto[]`**, totalPartitions,
  lastSeenAt.
- `ConsumerNodeDto` — nodeId, userAgent, partitions, lastSeenAt.
- `ConsumerPermissions` — per-resource access levels for templates,
  variants, versions, themes, environments, stencils, catalogs,
  generation, consumers.

The suite has not implemented any of it. Today we use a thin API-key
model: `apikeys` table, `X-API-Key` header,
`ApiKeyAuthenticationFilter` builds an `EpistolaPrincipal` with all
`TenantRole`s. Each API key is implicitly a consumer — its UUID lands
in `consumer_node_assignments.consumer_id` when a Valtimo node polls
`/generation/collect`.

The trigger for this work is operator visibility: when running the v0.3
collect protocol with one or more Valtimo instances polling the same
tenant, both the suite operator and the Valtimo operator want to see
"which consumers / which nodes are connected, when did they last
heartbeat, what's their partition share." The data exists in
`consumer_node_assignments`; it just isn't surfaced in either UI. The
contract's `listConsumers` (with its `nodes` field) is the natural
shape to expose it through.

## Goals

1. Implement the contract's Consumer Management API in the suite,
   matching the spec.
2. Suite admin UI (Thymeleaf/HTMX) for the operator: list / view /
   approve / reject / manage consumers per tenant + see their nodes.
3. Plugin admin tab (Angular) in the Valtimo UI: per Epistola plugin
   configuration, show the cluster of nodes (own + siblings)
   currently registered for that consumer.

## Non-goals

- Not a v0.3 deliverable. v0.3 ships with the API-key auth unchanged.
- Does **not** deprecate `X-API-Key`. The "do we keep API keys, replace
  them with self-signed JWTs, or both?" question is open and resolved
  per the recommendations below.

## Open questions and recommended answers

### 1. API-key bridging

Three options:

- **Coexist** _(recommended)_ — keep `X-API-Key` as a third
  `authMethod` (`api-key`). Add `api-key` to the contract enum (small
  bump). Each existing api key materializes as a Consumer row with
  `authMethod = api-key`, `status = active`, `grantedPermissions`
  derived from `TenantRole`s. Lowest-risk; preserves all existing
  integrations.
- Migrate every existing api key to a self-signed JWT consumer at
  upgrade time. Highest-risk; requires plugin operators to rotate.
- Coexist with deprecation: api-key consumers are read-only (cannot
  register new ones via UI). Encourages migration without forcing it.

### 2. Consumer ID format

The contract pattern is `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`, slug, 3–63
chars. Today's `consumer_node_assignments.consumer_id` holds UUIDs.
Two options:

- _(recommended)_ Add a `slug` column to `api_keys` (auto-generated
  from name on create, deduped) and use it as the consumer id;
  backfill existing rows. Means
  `consumer_node_assignments.consumer_id` migrates from UUID to slug
  — backfill required, but cleanly aligned with the spec and nicer
  for admin UI display.
- Relax the contract pattern to accept UUID strings too.

### 3. Permission enforcement

The contract's permissions model
(`templates: read|write|publish`, `generation: read|write`, …) is
richer than today's `TenantRole` enum. Phase 1 maps `TenantRole`s to
`ConsumerPermissions` for the response shape; Phase 4 adds real
fine-grained enforcement.

### 4. Authorization for `listConsumers`

Spec says `x-required-roles: [manager]`. For the cluster-view UX in
the plugin admin, the calling consumer is the api-key consumer itself
— forcing it through a manager api key would be awkward.

_(Recommended)_ add a small spec extension: `GET
/tenants/{tid}/consumers/me` for the consumer's self-view, no manager
role required. Keep the full `listConsumers` manager-only.

## Implementation phases

Each phase ends green (tests pass, deployable). Stopping after any
phase still leaves a useful product.

### Phase 1 — read-only model + listConsumers + getMyConsumer + nodes

#### Suite

- New table `consumers` (id slug, tenant_key, name, description,
  contact, status, auth_method, granted_permissions JSONB, public_key,
  expires_at, last_seen_at, created_at, updated_at). PK
  `(tenant_key, id)`.
- Migration backfills from existing `api_keys`: one consumer per api
  key, slug derived from `name` (kebab-case, deduped).
- Add `slug` column to `api_keys`, populated by the migration.
  `ApiKeyAuthenticationFilter` reads it and uses it as the principal's
  consumer-id (replacing the UUID).
- Migration step that backfills
  `consumer_node_assignments.consumer_id` from UUID → slug.
- New queries:
  - `ListConsumers(tenantKey, q?, status?, page, size)`
  - `GetConsumer(tenantKey, slug)`
  - `GetMyConsumer(tenantKey, callerSlug)`
    Each joins `consumer_node_assignments` to populate `nodes` and
    `lastSeenAt`.
- New REST controller `EpistolaConsumersApi` implementing
  `GET /tenants/{tid}/consumers`,
  `GET /tenants/{tid}/consumers/{cid}`,
  `GET /tenants/{tid}/consumers/me`.
- Permission mapping helper
  `TenantRolesToPermissions → ConsumerPermissions` DTO.
- ITs: list-with-nodes, get-by-slug, get-me, cross-tenant isolation.

#### Contract

- ~~Add `api-key` to `ConsumerDto.authMethod` enum.~~ **Already done**
  in epistola-contract `4394e86` (pulled forward into v0.3 since
  v0.3.0 is not yet released). Generated client + server expose
  `API_KEY("api-key")`. The `POST /consumers/register` description
  also calls out that api-key consumers don't go through self-service
  registration.
- Resolve `ConsumerDto.id` pattern (recommend: keep slug pattern,
  rely on backfill).
- Add new operation `GET /tenants/{tid}/consumers/me`.
- Bump `info.version` (coordinate with the v0.4 rebalance work, see
  `docs/v04-coordinated-rebalance.md`).

#### Plugin (backend)

- Bump generated client to the new contract version.
- `EpistolaService.getMyConsumer(configId)` — wraps the contract's
  `getMyConsumer` call.
- `EpistolaAdminService.getClusterConsumers()` — for each plugin
  configuration, calls `getMyConsumer` and returns DTOs.
- `EpistolaAdminResource.GET /api/v1/plugin/epistola/admin/clusters`
  → `List<ClusterConsumer>`.
- `ClusterConsumer` (record): configId, configTitle, tenantId,
  consumerId, consumerName, consumerStatus,
  nodes: List<ClusterNode>, errorMessage?.
- `ClusterNode` (record): nodeId, partitionsCount, partitions,
  lastSeenAt, isMe.

#### Plugin (frontend)

- New `ClusterConsumer` + `ClusterNode` interfaces in
  `frontend/plugin/src/lib/models/admin.ts`.
- `EpistolaAdminService.getClusterConsumers()` in
  `frontend/plugin/src/lib/services/epistola-admin.service.ts`.
- New "Cluster" tab in
  `frontend/plugin/src/lib/components/epistola-admin-page/`. Per-config
  card with a node table showing nodeId, "me" badge, last_seen_at as
  relative time, partitions count + collapsible partitions list.
- i18n strings in
  `frontend/plugin/src/lib/epistola.specification.ts`.

#### Suite admin UI (Thymeleaf/HTMX)

- New page `/tenants/{tid}/consumers` listing consumers with their
  nodes (collapsible per consumer). Columns: name, status, authMethod,
  nodes count, lastSeenAt, partitions span.
- Detail page `/tenants/{tid}/consumers/{slug}` with the full nodes
  table + permission overview.

### Phase 2 — register / approve / reject lifecycle

Suite:

- `POST /consumers/register` (no auth — self-signed flow).
- `POST /consumers/{cid}/approve`, `POST /consumers/{cid}/reject`.
- Permission gates use `TenantRole.MANAGER`.
- New `consumer_audit_log` table for who-approved-what observability.
- Self-signed JWT verification: validate JWT signed with the
  consumer's registered public key. Add `JwtAuthenticationFilter`
  alongside `ApiKeyAuthenticationFilter`.

Suite admin UI:

- Pending-consumers panel on the consumers page with approve / reject
  buttons.

Plugin: no mandatory work this phase.

### Phase 3 — update / delete / rotate public key

Suite:

- `PATCH /consumers/{cid}` (manager-only updates).
- `DELETE /consumers/{cid}` (manager-only). The spec already documents
  the partition-release behavior.
- `PUT /consumers/{cid}/public-key` (consumer's own rotation, signed
  with current key).

Suite admin UI: edit / delete buttons on the consumer detail page.

### Phase 4 — fine-grained permission enforcement

Replace `TenantRole`-based permission checks under `/api/tenants/...`
with the `ConsumerPermissions` model. Backwards-compat: api-key
consumers always get the equivalent of full permissions; JWT consumers
get only what was granted at approval. Large enough to deserve its own
follow-on plan with explicit migration steps.

## Critical files (Phase 1)

In `epistola-contract`:

- `spec/components/schemas/consumers.yaml` — add `api-key` to the
  authMethod enum.
- `spec/paths/consumers.yaml` — add `consumer-me` operation.
- `info.version` bump.

In `epistola-suite`:

- `modules/epistola-core/src/main/resources/db/migration/V27__consumers.sql`
  — new tables + backfill from `api_keys` + alter
  `consumer_node_assignments.consumer_id` (uuid → slug). Pre-flight
  backfill so the alter is safe. _Renumber to the next available slot
  at implementation time (V28 if the v0.4 rebalance migration lands
  first, etc.)._
- `modules/epistola-core/src/main/kotlin/app/epistola/suite/consumers/`
  — new package (`Consumer`, `ConsumerNode`, `ConsumerStatus`,
  `ConsumerAuthMethod`, `ConsumerPermissions`).
- `modules/epistola-core/src/main/kotlin/app/epistola/suite/consumers/queries/`
  — `ListConsumers.kt`, `GetConsumer.kt`, `GetMyConsumer.kt`.
- `modules/rest-api/src/main/kotlin/app/epistola/suite/api/v1/EpistolaConsumersApi.kt`
  — REST controller.
- `modules/rest-api/src/main/kotlin/app/epistola/suite/api/security/ApiKeyAuthenticationFilter.kt`
  — change principal `consumerId` to use the slug.
- `apps/epistola/src/main/kotlin/app/epistola/suite/handlers/ConsumersUiHandler.kt`
  — UI handler for the Thymeleaf page.
- `apps/epistola/src/main/resources/templates/consumers/list.html` /
  `detail.html` — Thymeleaf templates.
- `modules/epistola-core/src/test/kotlin/app/epistola/suite/consumers/`
  — query + handler ITs.

In `valtimo-epistola-plugin`:

- `gradle/libs.versions.toml` — bump `epistola-client`.
- `backend/plugin/src/main/java/app/epistola/valtimo/web/rest/EpistolaAdminResource.java`
  — add `GET /clusters`.
- `backend/plugin/src/main/java/app/epistola/valtimo/service/admin/EpistolaAdminService.java`
  — implement `getClusterConsumers()`.
- `backend/plugin/src/main/java/app/epistola/valtimo/web/rest/dto/ClusterConsumer.java`
  - `ClusterNode.java` — DTOs.
- `frontend/plugin/src/lib/services/epistola-admin.service.ts` — add
  `getClusterConsumers()` method.
- `frontend/plugin/src/lib/models/admin.ts` — interfaces.
- `frontend/plugin/src/lib/components/epistola-admin-page/epistola-admin-page.component.{ts,html,scss}`
  — new "Cluster" tab.
- `frontend/plugin/src/lib/epistola.specification.ts` — i18n labels.
- `backend/plugin/src/test/java/app/epistola/valtimo/service/admin/EpistolaAdminServiceTest.java`
  — extend with cluster-consumers tests.

## Verification (Phase 1 end-to-end)

1. Suite + plugin + Valtimo backend running (current local stack).
2. Hit suite endpoint directly:

   ```bash
   curl -X GET http://localhost:4000/api/tenants/demo/consumers/me \
     -H "X-API-Key: ..." \
     -H "User-Agent: epistola-contract/0.4.x probe" \
     -H "X-EP-Node-Id: probe"
   ```

   Expect a `ConsumerDto` with `nodes:[…]` populated from current
   heartbeats.

3. Hit plugin admin endpoint:

   ```bash
   curl http://localhost:8080/api/v1/plugin/epistola/admin/clusters \
     -H "Authorization: Bearer <admin-jwt>"
   ```

   Expect an array with one entry per configured plugin.

4. Open Valtimo admin UI → Epistola admin page → new Cluster tab.
   Each plugin config shows its consumer + node table. The current
   node has the "me" badge.

5. Open Suite admin UI → Tenants → demo → Consumers. Same data,
   suite-operator perspective.

6. Start a second Valtimo instance against the same suite + same api
   key → second node appears within `idle_timeout` on both UIs.

## Where to start

Begin with the suite migration + read-only queries (`Phase 1 → Suite`
bullets). That gives you `GET /me` + the data layer without any
contract bump (ship it as a suite-internal endpoint first, formalize
the wire shape and bump the contract once the data is proven). Once
the endpoint returns sensible data, do the plugin admin tab against
it; UI validates the shape before the contract bump locks it in. The
suite admin UI can land in parallel.
