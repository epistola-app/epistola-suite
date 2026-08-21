# Resource reference graph

The resource reference graph is a tenant-wide, read-only view of how catalog resources depend on
one another. It is an alpha feature, disabled by default; a tenant administrator can enable
**Resource graph** under **Settings → Features**. Then open **Resources → Resource graph**, search
for a resource, and traverse the resources
it uses, the resources that use it, or both. The initial view is deliberately focused rather than a
whole-tenant “hairball”; traversal depth is limited to one through three hops.

The graph is the first foundation for safe catalog-resource relocation. It does **not** move or
rewrite resources yet. A future relocation operation can use this authority to calculate the
closure of resources to move and the references that must be rewritten.

## Graph contract

A node is addressed by the tuple `(type, catalogKey, key)`. The supported resource types are asset,
code list, font, attribute, theme, stencil, and template. A directed edge means “source uses target”.
Every edge records:

- semantics: `runtime`, `authoring`, or `provenance`;
- qualification: an explicit catalog, a catalog-relative reference, or a tenant-global lookup;
- resolution: `resolved`, `missing`, or `ambiguous`;
- evidence: the owning variant or stencil version, lifecycle status, version, JSON location, and a
  pinned stencil version where applicable.

Draft and published versions make up the live graph. Archived template and stencil versions are
available through **Include archived history** and their evidence is marked historical. Published
theme snapshots contribute their pinned font-family references as provenance.

The following references are currently extracted:

| Source    | Target                      | Meaning                                    |
| --------- | --------------------------- | ------------------------------------------ |
| Template  | Theme                       | Default and per-document override          |
| Template  | Stencil                     | Inserted/pinned stencil provenance         |
| Template  | Asset                       | Image used during rendering                |
| Template  | Font                        | Inline styles and published snapshot fonts |
| Template  | Attribute                   | Variant authoring and validation           |
| Stencil   | Stencil, asset, font, theme | References embedded in versioned content   |
| Theme     | Font                        | Document styles and block style presets    |
| Attribute | Code list                   | Allowed-value binding                      |
| Font      | Asset                       | Uploaded font-face binary                  |

Assets and code lists have no outgoing catalog-resource references. A tenant's default-theme
setting is configuration rather than a catalog resource, so it is not represented as a node or
edge; template defaults that explicitly select a theme are represented.

## Resolution rules

Explicit references resolve only in their named catalog. Relative theme, stencil, and font
references resolve in the source resource's catalog. Unqualified asset IDs and variant attribute
keys use tenant-wide lookup: one matching node resolves, no matches is missing, and multiple matches
is ambiguous. The UI keeps unresolved references visible rather than silently dropping them.

## Architecture and API boundary

`GetTenantResourceGraph` in `epistola-core` builds a consistent snapshot on demand inside one read
transaction. It reads authoritative relational columns and versioned JSON; no derived graph table,
database migration, or cache is involved. `TenantResourceGraph.traverse` supplies the bounded BFS
used by the UI.

The explorer uses internal UI handlers only:

- `GET /tenants/{tenantId}/resource-graph`
- `GET /tenants/{tenantId}/resource-graph/nodes`
- `GET /tenants/{tenantId}/resource-graph/subgraph`
- `GET /tenants/{tenantId}/resource-graph/evidence`

These endpoints require `CATALOG_VIEW`. They are intentionally not a stable `/api` or MCP surface.
The Lit explorer and Cytoscape renderer are bundled and self-hosted, and the loader remains safe for
strict CSP and HTMX body swaps.

No demo catalog content is added for this feature: the explorer visualizes the cross-catalog theme,
stencil, asset, attribute, code-list, and font relationships already present in the bundled demo
and system catalogs. Changing those resources solely to demonstrate the visualization would alter
the graph rather than exercise a new resource capability.

## Follow-up work

Deletion and catalog-upgrade checks still use their existing specialized scanners. Migrate those
checks only after graph output has been compared against their behavior in production-shaped data.
Safe relocation should then add preview/execute commands with optimistic validation, compute the
dependency closure, distinguish references that must move from those that can be rewritten, and
make the resource writes and reference rewrites atomic.
