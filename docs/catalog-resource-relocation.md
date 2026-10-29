# Catalog resource relocation

> **Status:** Alpha, off by default, behind the `resource-relocation` toggle. The resource graph's
> hand-off to it also needs `resource-graph`. A crude move by decision:
> [ADR 0025](adr/0025-relocation-without-aliases.md) records why, and where relocation goes next.

Catalog resource relocation is an experimental, tenant-local operation for moving a resource to
another catalog, renaming its key, or both, between catalogs that can be changed here (ADR 0032). It is a **crude move**: the
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

A preview is required before execution. It opens with "Everything that uses it will be updated" and
reports:

- what the move means for releases (ADR 0042): each catalog it changes, with the kind its next
  release becomes and why (a source whose base release held the resource no longer contains it, a
  major; a target gains it, a minor; a catalog that now uses another depends on it), and the order
  they are released in, a catalog before those that now depend on it;
- the drafts it leaves alone (`left-behind-draft`), and a resource the draft it changes does not
  hold (`not-in-draft`, a blocker);
- references that will be rewritten to the destination address;
- relative references inside the moving resource's own content that will be pinned to the catalog
  they resolve against today; and
- blockers such as a destination collision, a catalog that cannot be changed here, or an unsupported resource type;
  and warnings, which inform without stopping the move — currently a released source catalog.

Execution obtains a tenant-scoped transaction lock, rebuilds the preview, and rejects a stale plan
fingerprint. It then updates mutable references and changes the resource's current catalog address
while retaining its stable `resource_id`.

## On the Organise page

The page and the single-resource dialog offer the refactorings by what they do: **Move or rename…**
on a resource (the dialog carries the draft the resource was opened in), and on the page a selection
moved to one catalog, or **Create a shared catalog…**, which creates the catalog (as Create catalog
would, with the catalog management permission) and makes it the destination; the move is then
previewed and applied as any other. A catalog created and not moved into stays, empty.

## Which drafts a move changes

A move changes one draft per catalog ([ADR 0042](adr/0042-reorganising-catalogs.md)): in the
catalog a resource moves out of, the draft the move names (`catalogVersion`, by default the next
version), and in every other catalog involved, its next version. Releases are never changed.

A catalog's other drafts are left alone, the resource and their references to it both: they keep
their copy at the old address, so each stays a coherent snapshot. The resource's identity goes with
the move, and with it what follows the identity (generation history, quality findings, load-test
runs); a copy left behind gets an identity of its own there (`split_draft_resource_identity`). The
preview names every draft left alone (`left-behind-draft`).

A catalog whose content now uses a resource that moved into another catalog depends on that
catalog's next version, not on a release of it: no release holds the resource yet. Releasing with
its dependencies releases that catalog first.

A draft left alone gets the move when a release made after it is merged into it, and a release made
from a draft left alone (a hotfix) merges into the draft that moved the resource. The merge deduces
the move from identities instead of recording it (`MergeMoves`): the merge base's rows keep the
identity they were cut with, and the registry says where that identity is now. The merge base and
the release are rebased through the moves before anything is classified, so a fix to a template that
names a moved theme is taken pointing at the theme where it went, and a fix to the moved theme
itself is a conflict that says where it went (`MergeResource.movedTo`). In another installation,
whose identities are its own, a move stays a removal and an addition.

## What follows a move, and what breaks

Follows the move:

- the resource's own rows — a template's variants, environment activations, quality findings and
  load-test runs; a code list's entries; a font's faces — because they name the resource by
  identity;
- working models and stencils that reference it, variant attribute keys, and theme styles naming a
  moved font, which are rewritten as part of the move.

Does not follow the move, and fails once it has happened:

- **Catalog releases** cut before the move. A release holds its own content and is never
  rewritten, so it keeps naming the old address; a reference into another catalog resolves in that
  catalog's releases, not in its working copy. Releasing again from the rewritten working copy
  brings a release up to date.
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

Graph extraction resolves references against current addresses only. A catalog export carries
each variant's working model as written and relativizes references to its own catalog; it does not
rewrite a reference to where a resource lives now.

## Generation history does not move

Moving a template carries its variants, data contract, environment activations, quality findings and
load-test runs with it — those describe the template's current state. Its generation history does
not: `documents` and `document_generation_requests` record the catalog the template lived in when a
document was produced, and that stays true afterwards.

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
- Source and destination must be different catalogs in the same tenant, both changeable here: not
  one Epistola supplies, and not on a run-only installation.
- A source catalog with a release **warns rather than blocks**. A subscriber that upgrades to a
  later release sees the resource gone rather than moved. Only the operator knows whether anyone
  consumes the catalog, and blocking made a catalog permanently unmovable after a single local
  release nobody ever pulled.
- Release content is never edited. When a resource leaves a catalog, the relative references in its
  own working content are pinned to the catalog they already resolve against: the bytes change, the
  meaning does not.
- A theme's styles are live configuration, rewritten like any working content: moving a font
  re-points every theme naming it, and a moving theme's own relative font references are pinned to
  the catalog they resolve against.
- A move that would leave two catalogs depending on each other is blocked. Catalog ordering is
  load-bearing for snapshot restore, which orders catalogs topologically and throws on a cycle, so
  an unchecked move could make a tenant's snapshots unrestorable. Only dependencies an export
  declares count: a font's face binaries travel inside the font, so where they are stored is not
  one.
- References are qualified with their catalog when content is written, so a reference keeps its
  meaning after its owner moves. Content written before that rule is pinned the same way when its
  owner moves, per the rule above.
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
never break published work — is set out in [ADR 0025](adr/0025-relocation-without-aliases.md) and
planned goal by goal in [From resource relocation to immutable publication](catalog-immutable-publication.md).
Until it lands, a move is only as safe as the operator's knowledge of what uses the resource.

This operation cannot be demonstrated by adding static content to the bundled demo catalog: the
feature is a state transition between two tenant-owned catalogs. Its representative scenario lives
in the command-driven relocation integration test instead.
