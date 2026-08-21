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

### Option D — Canonical move with address aliases and typed rewrites (candidate)

Move the canonical resource to the target catalog, preserve its stable internal identity and
version history, and record a tenant-local alias from its old address to its new address. Rewrite
mutable references to the canonical address. Published and archived payloads remain unchanged and
resolve through the alias.

**Pros:** preserves history and runtime behavior, gives new work a canonical address, and supports
subsequent moves without duplicating resources.

**Cons:** all resource resolution paths must understand aliases; aliases need collision, chaining,
export, and retention rules. This is more infrastructure than a direct update.

## Candidate decision

Adopt **Option D** with a two-step **preview then execute** protocol. The first implementation is an
alpha capability and supports only moves between two different `AUTHORED` catalogs in the same
tenant.

### Resource identity

A public resource address remains `(type, catalogKey, key)`. A move changes that address but does
not create a new logical resource:

- `type` and `key` are unchanged;
- the canonical `catalogKey` changes from source to target;
- stable internal IDs and version numbers are preserved where the domain has them;
- the target address must not already be occupied by either a resource or an alias;
- source and target catalogs must both exist, differ, and be `AUTHORED`.

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

### Published catalogs and portability

Aliases are a local compatibility mechanism, not part of the catalog wire format. New drafts are
canonicalized when created from published content. Export materialization resolves aliases and
emits canonical catalog addresses without mutating the stored published version.

A move marks the source catalog, target catalog, and every authored catalog whose effective export
changes as having unreleased changes. Their next release fingerprints the canonical export. ZIPs
and remote releases produced before the move remain immutable historical artifacts.

The move must not ship until export/import round-trip tests demonstrate that a tenant without the
local alias table receives a self-contained catalog with canonical references. If a reference shape
cannot be canonicalized during export, that reference blocks the move.

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
5. validates graph equivalence for previously resolved references;
6. records one audit summary with per-resource old and new addresses;
7. commits all changes together.

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
- Preview and execute integration tests create all domain state through production commands or the
  shared fixture/scenario DSL, never direct SQL setup.

## Delivery sequence

1. Make every runtime and export resolver use one alias-aware resource-address resolver, with parity
   tests against existing specialized resolution paths.
2. Add alias persistence, collision rules, graph evidence, and read/write behavior.
3. Add typed rewrite strategies and graph-equivalence validation one resource/reference kind at a
   time.
4. Add preview and execute commands with optimistic plan validation and transactional tests.
5. Add the alpha UI impact review and execution flow.
6. Consider alias cleanup, convenience dependency selection, REST, or MCP only after production
   behavior is understood.

## Consequences

- Moving a resource becomes a safe refactoring rather than a delete-and-recreate workflow.
- The old address remains reserved, potentially for a long time, in exchange for preserving
  immutable history and subscribed references.
- Resource lookup is slightly more complex and must be centralized before relocation is enabled.
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
