# Resource reference graph

> **Status:** Current, internal: no page, REST or MCP surface of its own.

The resource reference graph says how a tenant's catalog resources use one another. It is data, not
a page: organising catalogs uses it to refuse a move that would make catalogs depend on each other
and to say what a move means for each catalog's next release (ADR 0042), and merging uses it to
refuse taking a removal of something the draft still uses. People see the same facts where they
work: **Used by** on a resource, a catalog's **Dependencies** page, and the Organise preview. The
tenant-wide explorer page it once had (alpha) was removed in 2.0, since those cover its uses.

## Graph contract

A node is addressed by the tuple `(type, catalogKey, key)`. The supported resource types are asset,
code list, font, attribute, theme, stencil, and template. A directed edge means “source uses target”.
Every edge records:

- semantics: `runtime`, `authoring`, or `provenance`;
- qualification: an explicit catalog, a catalog-relative reference, or a tenant-global lookup;
- resolution: `resolved`, `missing`, or `ambiguous`;
- evidence: the owning variant or stencil and the JSON location.

The graph is built from the working copy: each template variant's working model and each stencil's
working copy. Since 2.0 there are no template or stencil versions, so every reference is live; what a
release holds is that release's own history, not part of this graph. The **Include archived
history** option no longer adds anything.

The following references are currently extracted:

| Source    | Target                      | Meaning                                 |
| --------- | --------------------------- | --------------------------------------- |
| Template  | Theme                       | Default and per-document override       |
| Template  | Stencil                     | Stencil instance                        |
| Template  | Asset                       | Image used during rendering             |
| Template  | Font                        | Inline styles                           |
| Template  | Attribute                   | Variant authoring and validation        |
| Stencil   | Stencil, asset, font, theme | References in the stencil's content     |
| Theme     | Font                        | Document styles and block style presets |
| Attribute | Code list                   | Allowed-value binding                   |
| Font      | Asset                       | Uploaded font-face binary               |

Assets and code lists have no outgoing catalog-resource references. A tenant's default-theme
setting is configuration rather than a catalog resource, so it is not represented as a node or
edge; template defaults that explicitly select a theme are represented.

## Resolution rules

Explicit references resolve only in their named catalog. Relative theme, stencil, and font
references resolve in the source resource's catalog. Unqualified asset IDs and variant attribute
keys use tenant-wide lookup: one matching node resolves, no matches is missing, and multiple matches
is ambiguous. Unresolved references are kept in the graph rather than silently dropped.

## Architecture and API boundary

`GetTenantResourceGraph` in `epistola-core` builds a consistent snapshot on demand inside one read
transaction. It reads authoritative relational columns and the stored JSON content; no derived graph
table, database migration, or cache is involved. `TenantResourceGraph.traverse` supplies a bounded
breadth-first traversal.

`ResourceReferenceSites` (`catalog/graph/ResourceReferenceSites.kt`) is the single authority for
which JSON shapes count as a catalog-resource reference, and for whether an unqualified one resolves
against the containing catalog or tenant-globally. Every consumer that reads or rewrites embedded
references traverses through it — the graph itself, catalog relocation
(`catalog/relocation/CatalogResourceRelocation.kt`), and catalog export
(`catalog/CatalogContentBuilder.kt`). A new embedded reference shape is added as a
`ReferenceSiteKind`, never as another walker; the shared traversal is what keeps discovery,
rewriting, and export from disagreeing about what a reference is.

Stored references name their catalog by key, which is unique in a tenant. On the wire a reference
into another catalog names the **alias** the catalog's dependency list declares for it
(`catalogDependencies[].alias`, contract ADR 0003, ADR 0034), and that declaration names the catalog
by registry, namespace and key; a reference carries no namespace or registry. This installation
declares every dependency under its catalog key (`CatalogDependencyDeclaration.alias`, column
`catalog_dependencies.alias`), so a stored reference already names its alias and travels unchanged:
a stencil insertion, an image, a theme override, a `fontFamily` in a template, stencil or theme, and
a template's theme, an attribute's code-list binding, a variant attribute's qualifier and a
catalog's default theme. The `namespace` and `registry` an earlier draft of wire v8 let a reference
carry are dropped when content is stored (`ResourceReferenceSites.qualifyRelative`), when it is
built for export (`CatalogContentBuilder.Outbound`) and when a release retained with them is read
(`ReleaseContentAssembler`); the 2.0 upgrade stores content without them. An archive that
declares a catalog under another alias than its key is refused on import
(`CATALOG_DEPENDENCY_ALIAS_UNSUPPORTED`): storing it would mean rewriting its references, which
changes its content hash, until catalogs are told apart by namespace and key (ADR 0034, phase 4).

## Follow-up work

Since 2.0 every stored content's references are indexed when it is stored (`resource_references`,
ADR 0033), for any draft or release. The graph, the **Used by** query (`FindResourceUsages`), the
deletion holder check and the upgrade analyser each still find references their own way; they move
onto the index one at a time, each compared against its current behaviour, so there is one answer to
what uses what.
