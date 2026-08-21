# ADR 0014: Safe relocation of authored catalog resources

- **Status:** Draft — discussion record, not accepted
- **Date:** 2026-08-21
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

### Option E — Canonical move with address aliases and typed rewrites (candidate)

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

## Candidate decision

Adopt **Option E** as the long-term model, with a two-step **preview then execute** protocol. This
recommendation is conditional on first making relational key updates safe and defining historical
relative-reference resolution. The first implementation is an alpha capability, supports only
moves between two different `AUTHORED` catalogs in the same tenant, and blocks every move whose
relational or immutable-reference shape is not yet supported.

Option D remains the lower-cost alternative if the desired product semantics are that old
references intentionally see a frozen snapshot. It is not chosen because the primary requirement
is that old and new addresses continue to identify one logical resource, and because retaining
frozen resources prevents a catalog reorganization from becoming clean in storage and export.

### Resource identity

A public resource address remains `(type, catalogKey, key)`. A move changes that address but does
not create a new logical resource:

- `type` and `key` are unchanged;
- the canonical `catalogKey` changes from source to target;
- stable internal IDs and version numbers are preserved where the domain has them;
- the target address must not already be occupied by either a resource or an alias;
- source and target catalogs must both exist, differ, and be `AUTHORED`.

Most current resource rows do not have a catalog-independent database identity. Their primary keys
contain `catalog_key`, for example `(tenant_key, catalog_key, id)` for templates, themes, stencils,
and assets and `(tenant_key, catalog_key, slug)` for fonts and code lists. "Preserve stable internal
identity" therefore means preserving the domain key, version history, and audit continuity while
changing the composite database key. It does not imply that a universal immutable resource UUID
already exists.

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

Aliases reserve their source address, point directly to the latest canonical address, and are
flattened after later moves. Tenant-global candidate discovery considers canonical resources only;
aliases do not create extra candidates or make an otherwise unique lookup ambiguous. An explicit
historical address may follow its alias after qualification.

Because the alias target is polymorphic across seven separate resource tables, a single ordinary
foreign key cannot enforce its existence. The relocation command validates the canonical target in
the same transaction, while deletion and subsequent relocation commands treat aliases as incoming
references. Introducing a central resource registry with immutable UUIDs could provide a generic
foreign-key target later, but is not required for the initial design.

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

Structural ownership constraints should use `ON UPDATE CASCADE` where changing the parent's
catalog must move the whole owned hierarchy. This includes template → variants → versions and
contracts, stencil → versions, code list → entries, and font → variants. The template chain crosses
module boundaries into generation history, quality findings, and load-test records; all those
constraints must participate before template moves can be enabled.

Semantic foreign keys either cascade or are updated by their typed rewrite strategy in the same
transaction. Examples are template and tenant default-theme references and attribute → code-list
bindings. Constraints involved in a multi-resource move may need to be deferrable so PostgreSQL
validates the completed selected set rather than an invalid intermediate statement order.

One current relationship cannot represent the desired post-move state: `font_variants.catalog_key`
identifies both the owning font and its backing asset. An asset cannot move away from its font, or a
font away from its asset, while that column serves both roles. Before supporting those independent
moves, add a separate `asset_catalog_key` to the asset foreign key. Until then the planner must
require the font and backing assets to move together and block selections that cannot satisfy that
co-location constraint.

Adding a catalog-independent surrogate resource UUID and migrating all relational references to it
would make future moves cheaper, but it is a much broader schema and API change. The candidate
direction instead uses cascades, deferrable constraints, and narrowly separated semantic catalog
columns. If implementing those changes proves more invasive than the surrogate model, this ADR must
be revisited before execution work starts.

### Reference rewriting

The graph discovers references; it does not authorize generic writes. Each movable edge kind must
have a registered, typed rewrite strategy owned by the corresponding domain. A strategy declares
which lifecycle states it may update and rewrites only the modeled field or JSON property.

During a move:

- mutable drafts and unversioned mutable configuration are rewritten to canonical addresses;
- references between moved resources are rewritten to their post-move addresses;
- outgoing references to resources that stay behind are made explicitly catalog-qualified when a
  relative reference would change meaning after the move;
- incoming mutable references are rewritten to the moved resource's new address;
- published and archived version payloads are never modified and continue through aliases;
- missing, ambiguous, or unsupported reference kinds are blockers unless the preview proves they
  are unrelated to the selected move.

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

A move does not edit a published or archived template version. For example, a published template
that stores `catalog-a/corporate-theme` keeps those exact bytes after the theme moves to
`catalog-b/corporate-theme`. At runtime the shared resolver follows the old address through the
alias to the canonical theme. The graph reports the edge as alias-resolved, and a future draft
created from that published version is canonicalized to the new address.

This preserves **logical identity**, not a snapshot at move time. If the referenced resource is a
live dependency and changes after moving, the old published template observes that change under the
same rules it did before the move. References that already pin a version or carry a resolved
snapshot remain pinned or snapshotted. If product semantics require every old reference to keep
seeing the resource exactly as it was at move time, Option D's frozen original is the appropriate
model instead of an alias.

### Published catalogs and portability

Aliases are a local compatibility mechanism, not part of the catalog wire format. New drafts are
canonicalized when created from published content. Export materialization resolves aliases and
emits canonical catalog addresses without mutating the stored published version.

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

### Preview, concurrency, and execution

`PreviewCatalogResourceMove` is a read-only query. It returns the proposed resources, aliases,
rewrites, unchanged cross-catalog dependencies, blockers, affected catalogs, and a deterministic
plan fingerprint. The UI presents this impact report before enabling execution.

`MoveCatalogResources` accepts the explicit selection, target catalog, and expected plan
fingerprint. In one database transaction it:

1. acquires a tenant-scoped relocation lock;
2. rebuilds the plan from authoritative data;
3. rejects the request as stale if the fingerprint differs;
4. applies canonical resource moves, aliases, and typed mutable rewrites;
5. lets cascades and typed relational updates move every dependent composite key;
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
- All relational dependants carry the canonical composite key after commit; no foreign key is
  weakened or dropped to make a move succeed.
- An immutable relative reference either retains a proven resolution base or blocks the move.
- Font and backing-asset moves obey the schema's co-location rule until `asset_catalog_key` is
  separated.
- Preview and execute integration tests create all domain state through production commands or the
  shared fixture/scenario DSL, never direct SQL setup.

## Delivery sequence

1. Inventory every ownership and semantic foreign key per resource type, including constraints in
   feature modules, and add move-shaped integration tests before changing the schema.
2. Add safe `ON UPDATE CASCADE`/deferrable behavior and separate catalog columns where one column
   currently represents two relationships, starting with `font_variants.asset_catalog_key`.
3. Define immutable relative-reference context and conservatively block historical shapes that
   cannot preserve resolution.
4. Make every runtime and export resolver use one alias-aware resource-address resolver, with parity
   tests against existing specialized resolution paths.
5. Add alias persistence, collision rules, graph evidence, and read/write behavior.
6. Add typed rewrite strategies and graph/foreign-key equivalence validation one resource/reference
   kind at a time.
7. Add preview and execute commands with optimistic plan validation and transactional tests.
8. Add the alpha UI impact review and execution flow.
9. Consider alias cleanup, convenience dependency selection, REST, or MCP only after production
   behavior is understood.

## Consequences

- Moving a resource becomes a safe refactoring rather than a delete-and-recreate workflow.
- The old address remains reserved, potentially for a long time, in exchange for preserving
  immutable history and subscribed references.
- Resource lookup is slightly more complex and must be centralized before relocation is enabled.
- Composite-key updates cascade through potentially large historical hierarchies, so template moves
  are materially more expensive and risky than moving a leaf resource.
- Some independent moves require schema normalization first; aliases cannot bypass relational
  co-location constraints.
- Existing immutable relative references reduce the initially supported move set unless their
  historical base catalog is preserved explicitly.
- Catalogs affected indirectly by rewritten or canonicalized references acquire unreleased changes.
- The graph remains derived from authoritative domain data; the move feature adds only alias and
  audit state, not a separately persisted graph.
- Initial delivery is deliberately synchronous and conservative. Unsupported reference shapes are
  blockers, not best-effort rewrites.

## Related

- [Resource reference graph](../resource-reference-graph.md)
- [ADR 0003: Stencil version in catalog export](0003-stencil-version-in-export.md)
- [ADR 0007: Catalog wire-format schema migrations](0007-catalog-wire-format-migrations.md)
- [Catalog versioning](../catalog-versioning.md)
