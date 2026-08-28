# ADR 0014: Safe relocation of authored catalog resources

- **Status:** Accepted
- **Date:** 2026-08-26
- **Discussants:** Epistola team
- **Tags:** catalog, resources, references, relocation, versioning

## Context

Catalog authors sometimes need to reorganize resources after a catalog has grown: a shared theme
belongs in a common catalog, a stencil should move into a product catalog, or an asset was created
in the wrong place. Today the catalog key is part of a resource's address, so changing catalogs is
not a cosmetic update. References may exist in relational columns, mutable drafts, published or
archived versioned JSON, catalog metadata, and released catalog exchange data.

The tenant resource-reference graph described in
[`docs/resource-reference-graph.md`](../resource-reference-graph.md) provides discovery and exact
evidence. It does not by itself define how a move preserves meaning. In particular, blindly
rewriting every discovered location would mutate published history, while moving only the resource
would break catalog-qualified references.

This ADR defines the semantics and safety boundary for moving resources between catalogs within one
tenant. Cross-tenant transfer, copying, renaming resource keys, and moving whole catalogs are out of
scope.

## Decision drivers

- References that resolved before a move must keep resolving to the same logical resource.
- Published and archived version payloads remain immutable.
- A move is previewable, deterministic, atomic, and safe under concurrent edits.
- Shared dependencies must not be swept into a move merely because they are reachable.
- Every reference rewrite is typed and explicit; generic JSON string replacement is forbidden.
- Existing relational integrity remains database-enforced throughout a move.
- Subscribed and system catalogs retain their read-only guarantees.
- Failed or stale plans leave no partial move behind.
- The result remains portable through catalog export rather than depending silently on local state.

## Considered options

### Option A — Change the catalog key and rewrite every reference

Move the resource row, then update every incoming and outgoing reference reported by the graph,
including published and archived version content.

**Pros:** the old address disappears and all stored references immediately use the new address.

**Cons:** published bytes and historical versions change without a new version number. Release
fingerprints, audit history, and reproducibility can no longer be trusted. A graph evidence path is
also not sufficient authority for an arbitrary write.

### Option B — Allow moves only when no references exist

Reject a move unless the selected resources form a disconnected component.

**Pros:** small and safe implementation; no reference rewriting.

**Cons:** excludes the real use case. Themes, stencils, fonts, and assets are useful precisely
because other resources refer to them.

### Option C — Copy to the target and leave the original resource

Create a second resource in the target catalog and rewrite mutable references opportunistically.

**Pros:** old references continue to resolve without new resolution machinery.

**Cons:** there are now two independently editable resources with unclear ownership and identity.
Changes can diverge, dependency analysis becomes ambiguous, and the operation is a copy rather than
a move.

### Option D — Copy to the target and freeze the original

Copy the resource to the target catalog, then mark the original as read-only and moved. Existing
references continue to use the frozen original while new and mutable references use the target
copy. The original is hidden from normal authoring but remains available to runtime resolution and
historical export.

**Pros:** published and archived references remain untouched; historical rendering does not need
alias-aware lookup; and an old catalog can remain self-contained when exported.

**Cons:** the resource's identity and future behavior split at move time. References using the old
address see a frozen snapshot, while references using the new address see subsequent changes. Two
copies also complicate tenant-global resolution, storage, audit history, deletion of the source
catalog, and deciding which version history is canonical.

If the read-only original redirects to the target instead of serving frozen content, it is not a
copy: it is an address alias represented as a resource placeholder. Because resource types live in
different domain tables with different constraints, a shared alias model expresses that behavior
more consistently than adding a placeholder state to every resource type.

### Option E — Canonical move with address aliases and typed rewrites

Move the canonical resource to the target catalog, preserve its stable internal identity and
version history, and record a tenant-local alias from its old address to its new address. Rewrite
mutable references to the canonical address. Published and archived payloads remain unchanged and
resolve through the alias.

**Pros:** preserves history and runtime behavior, gives new work a canonical address, and supports
subsequent moves without duplicating resources.

**Cons:** all resource resolution paths must understand aliases; aliases need collision, chaining,
export, and retention rules. Because catalog keys are embedded in current composite primary and
foreign keys, aliases alone cannot move relational data. Relative references in immutable content
also need a preserved resolution base or must block the move. This is substantially more
infrastructure than a direct update.

### Option F — Canonical move with ID-first internal references

Give every logical resource a stable tenant-local `resource_id` and make that ID the authoritative
target of internal references. Catalog key and slug remain the mutable, human-readable public
address. A move changes only catalog membership and the canonical address; references continue to
identify the same `resource_id`.

Ordinary relational relationships use a typed foreign key to the target resource ID. For references
embedded in versioned JSON, saving or publishing records the resolved target alongside the payload,
with its location, target `resource_id`, authored address, and resolution base. The
JSON continues to contain the readable address that authors and catalog tools understand; the
recorded ID is the internal resolution target. A current reference therefore has both useful forms:
an immutable identity for execution and a visible address for authoring, diagnostics, and export.

Aliases remain, but only as a compatibility mechanism for legacy address-only payloads, old URLs,
historical exports, and external callers. They are not the normal means by which a new internal
reference remains valid after a move. Export never treats tenant-local UUIDs as portable: it
materializes canonical addresses and explicit dependencies, and a future released-catalog move uses
a portable handoff identity rather than copying the local UUID.

**Pros:** a relocation becomes a small location update instead of a graph-wide rewrite; published
content that has a recorded target ID keeps its meaning without depending on an alias; foreign keys
and reference records preserve one logical identity across catalog moves; graph traversal can be
derived from typed records; and aliases can eventually be limited to compatibility boundaries.

**Cons:** this is the largest migration, and write-time qualification obtains most of its benefit
for a fraction of the cost. Every relationship and embedded reference needs a safe ID-resolution and
dual-write path; recorded references must be written by whatever writes the payload they describe;
existing immutable address-only payloads still need aliases; and tenant-local
UUIDs require an explicit portable identity or handoff mapping for exchange between installations.

## Decision

**Accepted: Option F — canonical move with ID-first internal references.**

A resource's address is currently also its primary key, which is why moving one is hard: references
name an address, so changing location changes identity, so every reference must be rewritten,
aliased, or blocked. Exchange between installations must be address-based, because tenant-local
UUIDs are not portable — but that is a requirement on the **wire**, and it was applied to
**storage**, where it was never needed.

Separating the three concepts the address currently conflates removes the problem rather than
managing it:

| Concept  | Representation                              | Mutable |
| -------- | ------------------------------------------- | ------- |
| Identity | `resource_id UUID`                          | never   |
| Location | `catalog_key` column, not part of any key   | yes     |
| Address  | `catalog_key` + slug, derived at boundaries | yes     |

Internal references target identity — relational ones as `resource_id` foreign keys, in-content
ones as `{ target, catalogKey, key }` where `target` resolves and the address records authored
intent and remains the fallback for an unresolvable reference. Address is computed at the edges
(export, URLs, REST, MCP) and mapped back on import. A move becomes a single column update plus a
slug-collision check, with nothing to rewrite because nothing internal stored an address. Published
payloads are never touched and keep resolving.

Identity is implemented with **E2 — a shared surrogate resource registry**, already in place for all
seven resource types.

Aliases are retained only as an **external-redirect layer** for bookmarked URLs and external
callers. They are expirable and never consulted by internal resolution.

The sequenced migration, per-table ordering, and the generation-history decision it forces are in
[Catalog resource identity migration](../catalog-resource-identity-migration.md).

### Why not Option E

Option E — canonical move with aliases and typed rewrites — is what the alpha shipped, completed by
qualifying references on write and reserving vacated addresses. It is the cheapest path to one
movable type and it works.

It was reconsidered because the goal is for **every** resource type to be movable. Option E's cost
is per-type and permanent: a rewrite strategy, a cascade migration, a reservation call, and a
legacy-content blocker for each of seven types, plus aliases as load-bearing internal
infrastructure and an address that can never be reused after a move. Option F's cost is one-time
and structural, and it ends with less machinery than the system has today.

At a scope of one alpha type the trade favoured E. At a scope of all types it favours F.

### Interim state

The alpha's Option E machinery stays in place and keeps working while the migration proceeds. Each
type that completes the migration stops needing it; the machinery is removed once no type does.
Write-time qualification remains valuable until in-content `target` is populated, and export
continues to emit relative same-catalog addresses either way — that is what keeps an exported
catalog installable under a different key.

Option D remains the lower-cost alternative if the desired product semantics are that old
references intentionally see a frozen snapshot. It is not chosen because the primary requirement
is that old and new addresses continue to identify one logical resource, and because retaining
frozen resources prevents a catalog reorganization from becoming clean in storage and export.

### Resource identity

A public resource address remains `(type, catalogKey, key)`. A move changes that address but does
not create a new logical resource:

- `type` and `key` are unchanged;
- the canonical `catalogKey` changes from source to target;
- a new stable internal `resourceId` and existing version numbers are preserved;
- the target address must not already be occupied by either a resource or an alias;
- source and target catalogs must both exist, differ, and be `AUTHORED`.

Most current resource rows do not have a catalog-independent database identity. Their primary keys
contain `catalog_key`, for example `(tenant_key, catalog_key, id)` for templates, themes, stencils,
and assets and `(tenant_key, catalog_key, slug)` for fonts and code lists. The stable `resourceId`
is therefore new infrastructure that must be backfilled before relocation is enabled. Under Option
F, it also becomes the target of new internal relationships and extracted JSON reference records.
Public URLs, commands, and catalog wire data continue to use resource addresses; the surrogate
identity is tenant-local and is not exported as a portable identifier.

System and `SUBSCRIBED` resources cannot be moved. References owned by those catalogs may continue
to resolve through an alias, but their stored content is never rewritten locally.

### Selection and dependency closure

The caller supplies an explicit set of resources. The planner uses the resource graph to report:

- references among selected resources;
- outgoing dependencies that will remain in their current catalogs;
- incoming references from resources outside the selection;
- unresolved or ambiguous references;
- resources that are private to the selection and could conveniently be added.

Traversal does **not** automatically determine what moves. Automatic transitive closure can pull in
widely shared themes, fonts, or assets and turn a small refactor into a tenant-wide reorganization.
The UI may offer **Add private dependencies**, but the final selection is explicit and visible in
the preview.

Moving several resources is one operation. References between selected resources are planned
against all of their target addresses, not rewritten one resource at a time.

### Alias semantics

A successful move records an alias from each old address to its canonical new address. Resource
resolution follows aliases before reporting a target missing. Aliases are tenant-scoped and typed;
an asset alias can never resolve a theme.

- Reads through an old address resolve to the canonical resource and expose that canonical address.
- Writes must use the canonical address; an old address produces a conflict response containing the
  new address rather than silently editing through the alias.
- A later move collapses the chain so every historical address points directly to the newest
  canonical address.
- Cycles are rejected.
- An old address cannot be reused while an alias exists.
- Alias source addresses remain resolvable even if the former source catalog is later removed;
  resolution must not require that catalog row to exist first.
- Aliases are retained while any published, archived, subscribed, or released evidence can refer
  to them. Automatic alias deletion is deferred until safe retention can be proven.

The graph resolves an aliased reference to the canonical node while retaining the raw address in
its evidence and marking the edge as `resolvedViaAlias`. This makes compatibility visible instead
of hiding it.

### Central address resolution

Central resolution canonicalizes an address; it does not become a universal repository that loads
every resource type. A shared `ResourceAddressResolver` accepts a tenant and fully qualified
`(type, catalogKey, key)` and returns both the requested and canonical address:

```text
stored reference
      │
      ▼
qualify relative or unqualified reference using its resolution context
      │
      ▼
ResourceAddressResolver
      ├─ canonical resource exists ──────────────┐
      └─ old address is an alias ──▶ canonical ─┤
                                                ▼
                                    domain-specific loader
```

Runtime rendering, graph resolution, dependency scanning, and export use the canonical result
before calling the existing theme, stencil, asset, font, attribute, code-list, or template loader.
The result includes `requested`, `canonical`, and `resolvedViaAlias`, so callers do not lose
diagnostic evidence. Mutation commands accept only canonical addresses; a write through an alias
returns a conflict containing the canonical address.

Under Option F, this resolver is an address boundary, not the primary internal dependency path.
Typed relationships and persisted reference records load the target by `resource_id`; they retain
the authored address only as evidence. Address-only legacy payloads, URLs, imports, and external
commands still pass through the resolver and may use an alias. This permits an incremental migration
without changing the public slug-based contract or rewriting immutable historical payloads.

Aliases reserve their source address, point directly to the latest canonical address, and are
flattened after later moves. Tenant-global candidate discovery considers canonical resources only;
aliases do not create extra candidates or make an otherwise unique lookup ambiguous. An explicit
historical address may follow its alias after qualification.

Aliases point to the shared registry by `(tenant_key, resource_id)`, so their canonical target is
protected by an ordinary foreign key even though resources live in seven domain tables. Deletion
and subsequent relocation commands still treat aliases as incoming references because foreign-key
existence alone does not decide whether an old address may be retired.

### Composite keys and foreign keys

PostgreSQL does not use the address resolver when checking a foreign key. Changing `catalog_key`
changes a resource's composite primary key, so the current constraints reject a parent update while
dependent rows still contain the old key. Relocation therefore needs explicit schema support in
addition to aliases.

The important current relationships include:

| Moved resource | Relational dependants                                                                            |
| -------------- | ------------------------------------------------------------------------------------------------ |
| Template       | Variants, versions, contracts, activations, generation history, quality findings, and load tests |
| Theme          | Template default-theme columns and the tenant default theme                                      |
| Stencil        | Stencil versions                                                                                 |
| Code list      | Code-list entries and bound attribute definitions                                                |
| Font           | Font variants                                                                                    |
| Asset          | Asset-backed font variants                                                                       |
| Attribute      | Primarily JSON variant assignments rather than relational foreign keys                           |

Three implementation strategies were considered for Option E.

#### E1 — Update composite keys with cascades

Keep each domain table's current catalog-qualified key and make the relevant constraints
`ON UPDATE CASCADE` or deferrable. Typed strategies update semantic references in the same
transaction.

**Pros:** preserves the current table identities and can be introduced relationship by
relationship.

**Cons:** a template move cascades through variants, versions, contracts, activations, generation
history, quality findings, and load tests. Every present and future module that references a
resource becomes coupled to relocation mechanics. Historical relational rows are physically
rewritten even though their logical subject did not change, and the polymorphic alias target still
cannot have one ordinary foreign key.

#### E2 — Shared surrogate resource registry (chosen)

Introduce one tenant-scoped identity row per logical resource. Conceptually:

```text
catalog_resources
  tenant_key
  resource_id UUID
  resource_type
  catalog_key
  resource_key
  PRIMARY KEY (tenant_key, resource_id)
  UNIQUE (tenant_key, resource_type, catalog_key, resource_key)
  FOREIGN KEY (tenant_key, catalog_key) REFERENCES catalogs
```

Domain rows and structural or semantic relational references migrate to stable resource IDs. A
move changes the registry row's `catalog_key`; it does not change the identity used by dependants.
Address aliases map an old typed address to `(tenant_key, resource_id)`. The registry stores
identity and current catalog membership only: resource content remains in its domain table, and the
reference graph remains derived rather than separately persisted.

Only the seven movable top-level resource types receive registry rows. Owned entities such as
template variants, template versions, stencil versions, code-list entries, and font variants are
not independently movable resources. Their ownership keys migrate from catalog-qualified parent
keys to the parent's stable `resource_id`; their own slug or sequence number remains unchanged.

Surrogate IDs are internal. Public URLs, commands, and catalog import/export continue to use typed
addresses. Import allocates local identities and resolves wire-format addresses to them; it never
requires IDs to be portable between tenants. A slug URL resolves
`(tenant, type, catalogKey, resourceKey)` through the registry and then loads the typed domain row.
An old GET URL may redirect to the canonical slug URL after alias resolution, but UUIDs never appear
in URLs or wire data.

**Pros:** catalog membership becomes an attribute rather than part of relational identity. Moves
do not cascade through historical ownership rows, aliases have a real foreign-key target, audit
records can retain one logical identity across moves, and future modules can reference resources
without depending on catalog relocation.

**Cons:** this is the largest forward migration. Every resource needs a backfilled registry row,
all relational references must migrate safely, and all address-based entry points need a reliable
address-to-ID boundary. Tenant isolation remains part of every key. A generic registry foreign key
proves that a resource exists but not that it has the expected type, so type correctness still
needs type-specific foreign keys where possible or explicit database/application validation.

The current `font_variants.catalog_key` represents both the owning font and its backing asset.
Under E2 those become separate stable `font_resource_id` and `asset_resource_id` relationships,
which removes the accidental same-catalog constraint and permits either resource to move
independently.

#### E3 — Domain-specific surrogate IDs without a shared registry

Give each resource table its own catalog-independent ID and migrate references directly to the
corresponding domain table.

**Pros:** migration can proceed per resource type, and type-specific relationships retain ordinary
foreign keys.

**Cons:** aliases and central address resolution still need seven polymorphic target paths, there
is no common foreign-key or uniqueness boundary for a resource identity, and generic graph,
authorization, audit, and relocation code keeps branching by resource type.

#### Identity implementation decision

Choose E2. E1 distributes relocation coupling across historical and future tables, while E3 omits
the main benefits of central identity. E2 is the required foundation for Option F: it gives every
reference record and alias one ordinary foreign-key target across all resource types. It has a
higher up-front migration cost, but makes later moves small and preserves database-enforced
identity across all resource types. It does not solve relative references in immutable payloads;
their resolution context remains a separate requirement described below.

### Recording resolved references

Relational references become ordinary `resource_id` foreign keys. References embedded in versioned
content record their resolved target too, so that a published payload keeps resolving after its
dependency moves without the payload being rewritten.

The preferred shape carries the target **inside** the reference — `{ target, catalogKey, key }` —
so a reference is self-describing and export simply strips `target`. Where that is impractical for
an existing content model, the equivalent is a sibling `resolved_references` JSONB column on
`template_versions`, `stencil_versions`, and the theme rows:

```jsonc
// template_versions.resolved_references
[
  {
    "kind": "stencil-insertion",
    "location": "templateModel.slots.children[0].props.stencilId",
    "targetResourceId": "0199...",
    "authoredCatalogKey": "letters",
    "authoredResourceKey": "header",
  },
]
```

This follows the idiom already used for exactly this problem: `template_versions.resolved_theme` is
a publish-time resolution snapshot in a sibling column, written by the same statement as the model,
and `referenced_paths` is a JSONB projection of the same kind.

A normalized `resource_references` table keyed by `(source_version_id, json_path)` was considered
and rejected. It would be written by every domain save and publish path — a second source of truth
with many writers and a permanent drift class, which the Consequences section below forbids and
`CLAUDE.md` states as the rule for `catalog/graph/`. Keying on a JSON path also couples the schema
to the template model's shape. The sibling column has one writer per row and no drift by
construction. Its target has no foreign key, but a missing target is already a modelled state
(`ReferenceResolution.MISSING`), and a foreign key there would wrongly block deleting a referenced
resource.

Extraction would stay behind `ResourceReferenceSites` either way; `resolved_references` is what
that traversal produced at write time, not a second opinion about it.

### Reference rewriting

The graph discovers references; it does not authorize generic writes. Each movable edge kind must
have a registered, typed rewrite strategy owned by the corresponding domain. A strategy declares
which lifecycle states it may update and rewrites only the modeled field or JSON property.

During the alpha and while migrating legacy address-only content:

- mutable drafts and unversioned mutable configuration are rewritten to canonical addresses;
- references between moved resources are rewritten to their post-move addresses;
- outgoing references to resources that stay behind are made explicitly catalog-qualified when a
  relative reference would change meaning after the move;
- incoming mutable references are rewritten to the moved resource's new address;
- published and archived version payloads are never modified and continue through aliases;
- missing, ambiguous, or unsupported reference kinds are blockers unless the preview proves they
  are unrelated to the selected move.

For an Option F reference with a recorded target ID, relocation leaves that identity unchanged.
Only its authored address is optionally canonicalized in mutable content. Published and archived
versions with recorded targets need neither a payload rewrite nor alias resolution; aliases remain
necessary for address-only historical versions.

After applying the strategies, the executor rebuilds the affected graph and verifies that every
previously resolved edge still resolves to the same logical target. The transaction is rolled back
if that invariant does not hold.

### Relative references in immutable content

Alias lookup starts only after a reference has a fully qualified address. Moving a referenced
target is straightforward when immutable content already stores its old catalog: that explicit old
address follows the alias. Moving the resource that owns a relative reference is harder. For
example, if a published template moves from `catalog-a` to `catalog-b` while its relative
`theme-x` dependency stays behind, resolving relative to the template's new catalog would search
for `catalog-b/theme-x` and never encounter an alias for `catalog-a/theme-x`.

Mutable drafts can be rewritten to an explicit canonical address. Published and archived payloads
cannot. A move of an immutable reference owner is therefore allowed only when the planner can prove
one of the following:

- the stored reference is already explicitly catalog-qualified;
- every relative target moves with the owner and retains the same relative meaning;
- immutable version metadata preserves the original reference-base catalog and runtime resolution
  uses that base without changing the payload.

Otherwise the relative reference is a preview blocker. For future publications, reference shapes
should be canonicalized to explicit catalog addresses where the model permits. Where relative
semantics must remain part of the format, template and stencil versions need an immutable
`reference_base_catalog_key` (or equivalent resolution snapshot) that does not change when their
owning resource moves. Existing immutable versions require a safe backfill or remain non-movable.

### Published templates and dependency semantics

A move does not edit a published or archived template version. For a historical template that
stores only `catalog-a/corporate-theme`, runtime follows the old address through the alias to the
canonical `catalog-b/corporate-theme`; the graph reports that edge as alias-resolved. A version
published after Option F is adopted also has a recorded target ID, so runtime loads the same theme
directly and keeps the address only as authored evidence. A future draft created from either form is
canonicalized to the new address.

This preserves **logical identity**, not a snapshot at move time. If the referenced resource is a
live dependency and changes after moving, the old published template observes that change under the
same rules it did before the move. References that already pin a version or carry a resolved
snapshot remain pinned or snapshotted. If product semantics require every old reference to keep
seeing the resource exactly as it was at move time, Option D's frozen original is the appropriate
model instead of an alias.

### Published catalogs and portability

Alias rows are a local compatibility mechanism and are not copied verbatim into the catalog wire
format. New drafts are canonicalized when created from published content. Export materialization
resolves aliases and emits canonical catalog addresses without mutating the stored published
version. A released relocation handoff, discussed below, is different: it is portable migration
metadata from which a subscriber creates its own local alias and preserves its existing identity.

A regular `ExportCatalogZip` after a move therefore behaves as follows:

- the source catalog no longer exports the moved resource;
- the target catalog exports the resource under its canonical address;
- an affected referencing catalog emits the canonical target address and corresponding
  cross-catalog dependency;
- no tenant-local alias or historical address is written into the ZIP;
- the fingerprint is computed from those actual canonicalized export bytes.

Today `ExportCatalogZip` always rebuilds the current working copy. It does not serve the immutable
content captured at an earlier release boundary. A move therefore marks the source catalog, target
catalog, and every authored catalog whose effective export changes as having unreleased changes.
Exporting one of those catalogs again produces `<latest-version>-dev` with a new fingerprint until
the author cuts a new release. The next release fingerprints the canonical export.

This is consistent with the existing catalog export drift policy, but it means "export again" is
not "download the old release again." ZIPs already produced before the move remain immutable
historical artifacts. If exact re-download of an earlier release becomes a requirement, Epistola
must retain and serve an immutable, self-contained release archive (including asset bytes) rather
than reconstruct it from the current database or from aliases. That release-artifact decision is
independent of resource relocation.

The move must not ship until export/import round-trip tests demonstrate that a tenant without the
local alias table receives canonical references and explicit portable dependencies. If a reference
shape cannot be canonicalized during export, that reference blocks the move.

### Scenario analysis and compatibility boundaries

Relocation affects more than direct reference resolution. The preview and implementation must cover
the following scenarios explicitly:

| Scenario                                                     | Required behavior or blocker                                                                                                                                            |
| ------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Mutable reference in the source or another authored catalog  | Rewrite it to the canonical target address and mark its owning catalog as changed.                                                                                      |
| Published or archived reference with an explicit old address | Preserve its bytes and resolve it through the local alias.                                                                                                              |
| Published or archived owner with a relative reference        | Preserve an immutable reference base or block the move, as described above.                                                                                             |
| Export the source catalog                                    | Omit the moved resource; canonicalize remaining references and declare new cross-catalog dependencies.                                                                  |
| Export the target catalog                                    | Include the moved resource under its canonical target address and compute a new fingerprint.                                                                            |
| Export a third catalog that references the moved resource    | Emit the target address and dependency even if its stored published bytes still contain the old address.                                                                |
| Import only a referencing catalog                            | Require the canonical dependency to exist first; never import an alias as a hidden substitute.                                                                          |
| Install an old ZIP in a fresh tenant                         | Reproduce that old release's original layout. It has no knowledge of a later relocation and must not be mixed silently with incompatible newer catalog releases.        |
| Tenant snapshot after a move                                 | Recreate canonical resources and references with new local surrogate IDs; aliases are unnecessary only if every exported historical reference was canonicalized safely. |
| Move introduces a catalog dependency cycle                   | Block while snapshot restore and other import orchestration require a dependency DAG.                                                                                   |
| Delete or recreate the source catalog                        | Keep historical alias addresses resolvable and reserved, even if their former catalog row is gone; recreating the catalog must not reclaim an aliased address.          |
| Delete the target catalog or moved resource                  | Block while retained aliases or other references target its stable identity.                                                                                            |
| Cached lookup, browser URL, or bookmark                      | Invalidate both old-address and canonical-address caches; reads may redirect to the canonical slug URL, while writes through the alias remain conflicts.                |
| Audit or generation history                                  | Store the stable resource ID and the address observed at event time, so reports can distinguish historical location from current location.                              |

#### Released and subscribed catalogs require a relocation handoff

Canonical export solves a fresh import, but it does not by itself solve an upgrade. Consider a
publisher that moves stencil `header` from catalog A to catalog B. A subscriber may already have:

- the stencil installed under A with a locally allocated `resource_id`;
- published content in A that still stores the old address;
- a locally authored catalog C that references A's stencil.

Installing B's next release as an ordinary addition allocates another local identity. Upgrading A's
next release then sees the old stencil as stale. Current stale-prune protection either blocks on the
references from A or C, or deletion would break them. The publisher's internal UUID and alias cannot
help because neither is portable, and upgrading A and B is not one atomic operation.

Released-resource relocation therefore needs an explicit exchange-level handoff, for example a
typed declaration in A's release saying that `(STENCIL, A, header)` continues as
`(STENCIL, B, header)`. Consumer-side orchestration must then:

1. require a compatible B release before applying A's removal;
2. reconcile B's incoming resource with A's existing local `resource_id` instead of creating a
   second logical resource;
3. create the consumer-local alias from the old address;
4. preserve incoming references from locally authored and older subscribed content;
5. advance both catalogs' installed state only through a retry-safe, deterministic protocol.

The exact wire shape and whether coordinated catalog upgrades must be atomic are a separate decision
before released-catalog moves are enabled. Until then, the planner blocks a resource that is present
in a release boundary or whose move would need to propagate to subscribers. Option D's frozen
original remains the simpler alternative when independent catalog upgrades are a hard requirement.

#### Resource-specific exchange gaps

Not every current reference shape can express the dependency created by a move:

- stencil, theme, font, and code-list dependencies can carry a catalog key;
- asset references can carry a catalog key in document JSON, but the current catalog dependency
  entry treats the asset UUID as tenant-global and does not identify the owning catalog or a
  compatible release;
- variant attribute assignments contain attribute keys, while the current dependency model has no
  catalog-qualified attribute dependency.

The alpha planner must block moves that create a dependency the current wire format cannot represent
unambiguously. Extending and migrating that wire format is required before those resource/reference
kinds are enabled.

### Preview, concurrency, and execution

`PreviewCatalogResourceMove` is a read-only query. It returns the proposed resources, aliases,
rewrites, dependency additions and removals, release/subscription handoffs, affected fingerprints,
catalog dependency cycles, blockers, affected catalogs, and a deterministic plan fingerprint. The
UI presents this impact report before enabling execution.

`MoveCatalogResources` accepts the explicit selection, target catalog, and expected plan
fingerprint. In one database transaction it:

1. acquires a tenant-scoped relocation lock;
2. rebuilds the plan from authoritative data;
3. rejects the request as stale if the fingerprint differs;
4. updates canonical catalog membership in the resource registry and creates aliases;
5. applies typed relational and mutable JSON rewrites while stable foreign keys remain unchanged;
6. validates foreign keys and graph equivalence for previously resolved references;
7. records one audit summary with per-resource old and new addresses;
8. commits all changes together.

There is no persisted move plan or background job initially. A database or validation failure rolls
back the whole operation. A completed move is reversed only by previewing and executing another
move; it is not undone by deleting aliases or replaying partial writes.

### Permissions and API boundary

Preview requires catalog view permission. Execute requires catalog management permission for the
tenant and must enforce catalog mutability again inside the command.

The first UI uses internal `/tenants/**` handlers. A stable REST or MCP write surface is out of scope
until the alpha workflow and error model have settled.

## Invariants and acceptance criteria

- A move never mutates published or archived payload bytes.
- Every reference resolved before execution resolves to the same logical resource afterwards.
- No resource or reference is changed when the plan is stale or contains blockers.
- A selected set either moves completely or not at all.
- Source and target cannot cross tenants or include read-only catalogs.
- Target collisions, alias collisions, cycles, missing references, ambiguous references, and
  unsupported rewrites are explicit preview blockers.
- Drafts and new exports contain canonical addresses; old immutable versions work through aliases.
- A fresh export/import resolves without access to the publisher's internal resource IDs or aliases.
- A released resource is not relocated until subscribers can reconcile the old and new catalog
  entries as one local identity through an explicit, retry-safe handoff.
- A move does not introduce a catalog dependency cycle while snapshot restore requires topological
  ordering.
- Historical alias addresses remain reserved across source-catalog deletion and recreation.
- All relational dependants still reference the same stable resource IDs after commit; no foreign
  key is weakened or dropped to make a move succeed.
- An immutable relative reference either retains a proven resolution base or blocks the move.
- Font variants use distinct stable font and asset relationships before independent moves are
  enabled.
- Preview and execute integration tests create all domain state through production commands or the
  shared fixture/scenario DSL, never direct SQL setup.

## Delivery sequence

1. Inventory every ownership and semantic foreign key per resource type, including constraints in
   feature modules, and add move-shaped integration tests before changing the schema.
2. Add the shared resource registry and backfill a stable identity for every resource.
3. Migrate relational foreign keys to stable identities, including distinct font and backing-asset
   relationships, without weakening tenant or type integrity.
4. Qualify relative references when content is written, so new stored references always name
   their catalog and aliases always have an address to attach to.
5. Reserve addresses vacated by a relocation, with an explicit release that previews what stops
   resolving. `requireAddressAvailable` is shared, but each resource type's create command must
   call it: **making a new type movable means wiring the guard into that type's create path in the
   same change**, because no alias can exist for a type before it is movable and nothing fails if
   the call is missed.
6. Define immutable relative-reference context and conservatively block historical shapes that
   cannot preserve resolution.
7. Make every runtime and export resolver use one alias-aware resource-address resolver, with parity
   tests against existing specialized resolution paths.
8. Add alias persistence, collision rules, graph evidence, and read/write behavior.
9. Add typed rewrite strategies and graph/foreign-key equivalence validation one resource/reference
   kind at a time.
10. Extend exchange dependencies for resource types that cannot yet express a canonical
    cross-catalog reference, and add export/import/snapshot round-trip tests.
11. Design the released-catalog relocation handoff and coordinated subscriber-upgrade behavior;
    keep released resources blocked until it is implemented.
12. Add preview and execute commands with optimistic plan validation and transactional tests.
13. Add the alpha UI impact review and execution flow.
14. Consider alias cleanup, convenience dependency selection, REST, or MCP only after production
    behavior is understood.

## Consequences

- Moving a resource becomes a safe refactoring rather than a delete-and-recreate workflow.
- The old address remains reserved, potentially for a long time, in exchange for preserving
  immutable history and subscribed references.
- Resource lookup is slightly more complex and must be centralized before relocation is enabled.
- Adopting stable identities requires a large forward migration and changes many foreign keys, but
  subsequent moves do not rewrite historical ownership hierarchies.
- The shared registry is foundational identity metadata, not a replacement for domain resource
  tables and not a separately persisted reference graph.
- **Interim:** stored references are qualified on write, so a reference's catalog is explicit and an
  alias always has a concrete address to attach to. Assets stay unqualified because they resolve
  tenant-globally. This is superseded per type as in-content `target` is populated.
- **Interim, until a type completes the identity migration:** an address vacated by a relocation is
  reserved and can only be reused by explicitly releasing the alias, which is previewable. Once
  references target identity, address reuse is unambiguous and the reservation is removed.
- Aliases are the ordinary mechanism by which a published reference survives a move, not merely a
  legacy compatibility boundary. They are permanent, flat (each points straight at a stable
  identity), and cheap to resolve in one indexed lookup.
- Immutable references written before write-time qualification are a bounded, non-growing legacy
  tail. They still reduce the supported move set: a resource whose published versions carry an
  unqualified outgoing reference stays blocked until a frozen resolution base is recorded for those
  rows. Nothing authored from now on joins that set.
- Catalogs affected indirectly by rewritten or canonicalized references acquire unreleased changes.
- Moving a resource across catalog boundaries can introduce new dependency edges, release-ordering
  requirements, or cycles; it is not only a storage operation.
- Tenant-local surrogate IDs make moves cheap inside one tenant but do not establish identity across
  exports. Released relocation requires explicit portable handoff metadata and upgrade orchestration.
- Current subscriber stale-prune protection correctly blocks a removal that still has incoming
  references; relocation must integrate with it rather than bypass it.
- The graph remains derived from authoritative domain data; the move feature adds only alias and
  audit state, not a separately persisted graph.
- Initial delivery is deliberately synchronous and conservative. Unsupported reference shapes are
  blockers, not best-effort rewrites.

## Deferred

- Released-catalog relocation handoff and coordinated subscriber upgrade.
- Renaming resource keys and moving whole catalogs. Both become straightforward once identity is
  separated from address, but neither is in scope here.
- Expiry policy for external alias redirects.
- Whether a resource may move while it has unreleased changes in a catalog that has been
  published.
- REST and MCP surfaces for relocation, once the command contract has settled.

## Related

- [Resource reference graph](../resource-reference-graph.md)
- [ADR 0003: Stencil version in catalog export](0003-stencil-version-in-export.md)
- [ADR 0007: Catalog wire-format schema migrations](0007-catalog-wire-format-migrations.md)
- [Catalog versioning](../catalog-versioning.md)
