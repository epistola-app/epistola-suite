# Catalog resource relocation

> **Status:** Alpha, off by default, behind the `resource-relocation` toggle. The resource graph's
> hand-off to it also needs `resource-graph`. A crude move by decision:
> [ADR 0025](adr/0025-relocation-without-aliases.md) records why, and where relocation goes next.

Catalog resource relocation is an experimental, tenant-local operation for moving an authored
resource to another authored catalog, renaming its key, or both. It is a **crude move**: the
resource keeps its identity and editable references follow it, but nothing is left at the old
address. Anything that still names the old address stops resolving — move the wrong thing and
things break.

Enable `resource-relocation` for a tenant to use it; it is alpha and defaults off. The organise page
does not need the resource graph, but the graph's "Organise catalogs" hand-off appears only when
both toggles are on.

Previewing a move needs catalog view; applying one needs catalog management. A reader who can only
preview is told so on the page instead of being offered Move.

The alpha supports all seven relocatable resource types: templates, stencils, themes, fonts,
images, code lists and variant attributes. Every one of them is keyed by its stable `resource_id`
rather than by its address, so a move is a single-row update and nothing cascades — see
[`catalog-resource-identity-migration.md`](catalog-resource-identity-migration.md). An image is the
one type that cannot be renamed: its key is a generated identifier, and an unqualified image
reference resolves by that key alone.

A preview is required before execution and reports:

- draft references that will be rewritten to the destination address;
- published references that keep naming the old address, and so will stop resolving;
- relative references inside the moving resource's own versions, published ones included, that
  will be pinned to the catalog they resolve against today; and
- blockers such as a destination collision, subscribed catalogs, or an unsupported resource type;
  and warnings, which inform without stopping the move — currently a released source catalog.

Execution obtains a tenant-scoped transaction lock, rebuilds the preview, and rejects a stale plan
fingerprint. It then updates mutable references and changes the resource's current catalog address
while retaining its stable `resource_id`.

## What follows a move, and what breaks

Follows the move:

- the resource's own rows — a template's variants, versions, contract versions, environment
  activations, quality findings and load-test runs; a stencil's versions; a code list's entries;
  a font's faces — because they name the resource by identity;
- drafts that reference it, variant attribute keys, and theme styles naming a moved font, which
  are rewritten as part of the move.

Does not follow the move, and fails once it has happened:

- **Published versions** naming the old address. Content embedded or snapshotted at publish keeps
  rendering — an inserted stencil, the frozen `resolved_theme` — but anything resolved by address
  at render time does not: a moved font fails the font integrity check, and a moved image renders
  without the image. Live resolution of a theme by an old address — a draft, a preview — falls back
  to the tenant default theme.
- **Export, release and snapshot** of a catalog whose latest published versions name a moved
  resource: export validates the content it carries and refuses. Reopening each such template,
  pointing its draft at the new address and publishing it makes the catalog exportable again.
- **External callers**: UI pages, REST and MCP answer for the address a resource occupies now; an
  old one is "not found". Bookmarks and integrations have to be updated. One known exception: a
  stencil's page looks the stencil up by key alone, ignoring the catalog in its URL (as in 1.1.0),
  so a moved stencil still answers at its old page address.
- **Queued generation**: a request records the address it was made against. One queued before its
  template moved fails when it is processed.
- **Reuse of the old address**: it is free at once. Creating a resource there succeeds, and
  anything still naming that address now means the new resource.

## Resolution and export

`catalog_resources` is the stable identity registry; it is not a separately maintained reference
graph. Domain rows retain their current catalog-and-slug address and synchronize that address to
the registry, which is also what allocates each resource's `resource_id` and lets a snapshot
restore keep it.

Graph extraction resolves references against current addresses only, so a published reference to a
moved resource shows as missing. A catalog export carries each template's latest published
version as written and relativizes references to its own catalog; it does not rewrite a reference
to where a resource lives now.

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

- Every catalog resource type: stencils, variant attributes, templates, code lists, images, fonts
  and themes. A type added to `CatalogResourceType` without a `MovableResource` entry produces an
  `unsupported-resource-type` blocker. Supported types are declared in `MovableResource`; adding an
  entry is the last step of making a type movable, not the first.
- Source and destination must be different authored catalogs in the same tenant.
- A source catalog with a release **warns rather than blocks**. A subscriber that upgrades to a
  later release sees the resource gone rather than moved. Only the operator knows whether anyone
  consumes the catalog, and blocking made a catalog permanently unmovable after a single local
  release nobody ever pulled.
- Immutable version JSON is never edited, with one exception: when a resource leaves a catalog, the
  relative references inside its own versions — published ones included — are pinned to the catalog
  they already resolve against. The bytes change; the meaning does not. The same pin covers a
  published version's frozen theme snapshot (`resolved_theme`) when the theme it resolves fonts
  through changes catalog: a font the snapshot names without a catalog is pinned, and its integrity
  pin rekeyed. Publishes now freeze that catalog themselves, so only versions published before
  that have anything to pin.
- A theme's styles are live configuration, rewritten like a draft: moving a font re-points every
  theme naming it, and a moving theme's own relative font references are pinned to the catalog they
  resolve against.
- A move that would leave two catalogs depending on each other is blocked. Catalog ordering is
  load-bearing for snapshot restore, which orders catalogs topologically and throws on a cycle, so
  an unchecked move could make a tenant's snapshots unrestorable. Only dependencies an export
  declares count: a font's face binaries travel inside the font, so where they are stored is not
  one.
- References are qualified with their catalog when content is written, so a published reference
  keeps its meaning after its owner moves. Content written before that rule is pinned the same way
  when its owner moves, per the exception above.
- Export relativizes references back to their own catalog, so an exported catalog stays installable
  under a different key. Stored form is absolute, wire form is relative.
- `/tenants/{tenantId}/catalogs/organise` is the product surface: a browser across catalogs that
  allows moving. One destination is chosen for the whole selection; a row may take its own, which
  is also how a rename is expressed. Deep-linkable via `?resource=<type>:<catalog>/<key>`,
  repeatable.
- `/tenants/{tenantId}/catalogs/organise/move?resource=<type>:<catalog>/<key>` is the focused
  single-resource surface, for linking from the resource's own page. It answers with a dialog to
  HTMX and a full page otherwise, so the same URL works embedded and pasted. After a move from the
  resource's own page, the browser goes to that page at the new address. The resource graph links
  to it rather than hosting the operation — the graph diagnoses, this applies. REST and MCP
  operations are intentionally deferred until the command contract and authorization model have
  settled.

The direction beyond this alpha — published content that retains its own inputs, so that a move can
never break published work — is set out in [ADR 0025](adr/0025-relocation-without-aliases.md);
until it lands, a move is only as safe as the operator's knowledge of what uses the resource.

This operation cannot be demonstrated by adding static content to the bundled demo catalog: the
feature is a state transition between two tenant-owned catalogs. Its representative scenario lives
in the command-driven relocation integration test instead.
