# Catalog resource relocation (alpha)

Catalog resource relocation is an experimental, tenant-local operation for moving an authored
resource to another authored catalog without invalidating references to its old public address.
Enable both `resource-graph` and `resource-relocation` for a tenant to use it. The relocation toggle
is alpha and defaults off.

The alpha supports stencils, variant attributes and templates. A preview is required before
execution and reports:

- draft references that will be rewritten to the destination catalog;
- immutable published references that will continue to resolve through an alias;
- relative references inside the moving resource's own versions, published ones included, that
  will be pinned to the catalog they resolve against today; and
- blockers such as a destination collision, subscribed catalogs, or an unsupported resource type;
  and warnings, which inform without stopping the move — currently a released source catalog.

Execution obtains a tenant-scoped transaction lock, rebuilds the preview, and rejects a stale plan
fingerprint. It then updates mutable references, creates a typed alias from the old slug address,
and changes the resource's current catalog address while retaining its stable `resource_id`.

## Resolution and export

`catalog_resources` is the stable identity registry; it is not a separately maintained reference
graph. Domain rows retain their current catalog-and-slug address and synchronize that address to
the registry. `catalog_resource_aliases` maps an old typed address to the stable identity.

Graph extraction resolves aliases centrally, so immutable published evidence still points to the
moved stencil. A source-catalog export does not serialize tenant-local aliases. Instead, it
materializes old stencil references as the canonical destination address and emits the resulting
cross-catalog dependency. The moved stencil itself appears only in an export of the destination
catalog.

## Generation history does not move

Moving a template carries its variants, versions, contract versions, environment activations,
quality findings and load-test runs with it — those describe the template's current state. Its
generation history does not: `documents` and `document_generation_requests` record the catalog the
template lived in when a document was produced, and that stays true afterwards.

The consequence is deliberate: deleting a template no longer purges its generation history, because
the foreign keys that used to cascade that deletion are the same ones that would have dragged the
address along. History outliving its template is the better answer for an audit record, and
partition retention still ages it out.

The link back to the template is kept by identity rather than by address: both tables carry
`template_resource_id`, filled on insert by a trigger. It is deliberately not backfilled. The column
is nullable, and because these tables are partitioned by `created_at` with retention, every
surviving row carries it within one retention window — the backfill completes on its own. Rows
written before it existed resolve through their recorded address instead.

## Initial boundaries

- Stencils, variant attributes, templates and code lists. Other types produce an `unsupported-resource-type` blocker
  until their table is re-keyed onto its stable identity — see
  [Catalog resource identity migration](catalog-resource-identity-migration.md). Supported types are
  declared in `MovableResource`; adding an entry is the last step of making a type movable, not the
  first.
- Source and destination must be different authored catalogs in the same tenant.
- A source catalog with a release **warns rather than blocks**. Within the installation the move is
  well-defined — the alias keeps local references resolving — but aliases are tenant-local, so a
  subscriber that upgrades to a later release sees the resource gone rather than moved. Only the
  operator knows whether anyone consumes the catalog, and blocking made a catalog permanently
  unmovable after a single local release nobody ever pulled. The portable handoff (ADR 0014 step 11)
  is still the real answer; the warning is what carries the risk until then.
- Immutable version JSON is never edited, with one exception: when a resource leaves a catalog, the
  relative references inside its own versions — published ones included — are pinned to the catalog
  they already resolve against. The bytes change; the meaning does not. Note this is no longer
  bounded by the released-catalog rule: since a released catalog only warns, a move out of one can
  rewrite bytes that a release already covered. Only legacy content written before references were
  qualified on write has anything left to pin, so the exposure shrinks to zero over time rather
  than growing.
- A move that would leave two catalogs depending on each other is blocked. Catalog ordering is
  load-bearing for snapshot restore, which orders catalogs topologically and throws on a cycle, so
  an unchecked move could make a tenant's snapshots unrestorable — surfacing later, to whoever is
  trying to recover, with nothing tying it back to the move.
- An address a moved resource left behind is reserved: creating a resource there is rejected until
  the alias is explicitly released, which previews what stops resolving first.
- References are qualified with their catalog when content is written, so a published reference
  keeps its meaning after its owner moves. Content written before that rule is pinned the same way
  when its owner moves, per the exception above.
- Export relativizes references back to their own catalog, so an exported catalog stays installable
  under a different key. Stored form is absolute, wire form is relative.
- `/tenants/{tenantId}/catalogs/organise` is the product surface: a browser across catalogs that
  allows moving. Deep-linkable via `?resource=<type>:<catalog>:<key>`, repeatable. The resource
  graph links to it rather than hosting the operation — the graph diagnoses, this applies. REST and
  MCP operations are intentionally deferred until the command contract and authorization model have
  settled.
- An address a template, stencil or attribute has moved away from keeps working on every surface.
  REST and MCP resolve it to the canonical address before dispatching
  (`ResolveCanonicalResourceAddress`, deliberately authorisation-free: the operation that follows
  carries the permission, and gating the resolution would fail a generate-only key on every
  address). A UI `GET` redirects to the canonical URL, and everything beneath the resource —
  variants, versions, contract — redirects with it.

This operation cannot be demonstrated by adding static content to the bundled demo catalog: the
feature is a state transition between two tenant-owned catalogs. Its representative scenario lives
in the command-driven relocation integration test instead.
