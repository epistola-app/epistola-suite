# Catalog resource relocation (alpha)

Catalog resource relocation is an experimental, tenant-local operation for moving an authored
resource to another authored catalog without invalidating references to its old public address.
Enable both `resource-graph` and `resource-relocation` for a tenant to use it. The relocation toggle
is alpha and defaults off.

The first vertical slice supports stencils. A preview is required before execution and reports:

- draft references that will be rewritten to the destination catalog;
- immutable published references that will continue to resolve through an alias;
- relative references inside the moving stencil that must be qualified; and
- blockers such as a destination collision, subscribed catalogs, released source catalogs, or an
  unsupported resource type.

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

## Initial boundaries

- Stencils only; other top-level resource types produce an explicit preview blocker.
- Source and destination must be different authored catalogs in the same tenant.
- A source catalog with a release is blocked until subscriber/release handoff semantics exist.
- Immutable version JSON is never edited.
- The web resource graph is the only product surface in this alpha. REST and MCP operations are
  intentionally deferred until the command contract and authorization model have settled.
- Old public stencil URLs are not redirected yet; the resolver is currently used by graph and
  export paths.

This operation cannot be demonstrated by adding static content to the bundled demo catalog: the
feature is a state transition between two tenant-owned catalogs. Its representative scenario lives
in the command-driven relocation integration test instead.
