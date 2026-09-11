# ADR 0022: Installation bindings — what an installed catalog leaves to the installer

- **Status:** Draft
- **Date:** 2026-09-09
- **Deciders:** Epistola team
- **Tags:** catalog exchange, installation, dependencies, relocation, assets, themes, fonts, stencils
- **Context:** Installing catalogs from Epistola Exchange
  ([`docs/catalog-exchange-installation.md`](../catalog-exchange-installation.md))
- **Related:** [ADR 0001](0001-stencil-placeholders.md) and [ADR 0002](0002-stencil-parameters.md)
  (the holes a publisher can leave today), [ADR 0007](0007-catalog-wire-format-migrations.md) (how
  the wire format changes), [ADR 0020](0020-where-a-catalog-resource-address-lives.md) (addresses
  and identities), [ADR 0021](0021-catalog-upstream-release-discovery.md) (the inbound direction)

> **Status:** Draft. A design record for three related requests. It is written to be accepted or
> argued with, not to describe current behaviour. **Read the addendum below before D4-D6: roles were
> replaced by overridable resources on 2026-09-10, and several supporting claims in this record are
> wrong.**

## Addendum, 2026-09-10 — roles are superseded, and errata

### D4-D6 are superseded by overridable resources

D4 marked a **role** at each reference site, declared roles on the manifest, and had the importer
rewrite stored content on every install and upgrade to apply a binding. The same outcome is reached
by marking the **resource** overridable and binding a replacement to it: the overridable resource's
identity already names the hole, so nothing is marked at reference sites.

That removes the `role` marker on four site kinds and with it the contract changes to
`ThemeRefOverride`, `FontRef`, node props and `TemplateResource.themeRole`; the manifest `roles[]`
section; the exporter check that every used role is declared with a matching kind; the "all sites of
a role must share one default" rule; the default-address columns; and the content rewriter. Three
tables become one, and the contract change becomes one boolean on four resource classes.

The decisive reason is upgrades. `sync_catalog_resource_identity` adopts the identity already
registered at an address when a resource is re-imported, so a binding keyed on that identity survives
a content change with no re-application at all. Two things also come out better: the hole stays
visible in the reference graph and in a re-export, where rewriting would have baked the installer's
logo in as the publisher's choice; and Exchange needs no new storage, because an overridable resource
is already a row in `catalog_release_resource`.

**Overridable, not placeholder.** A placeholder is the special case where the shipped content is a
stand-in. Making the flag `overridable` lets the publisher ship the real thing, which keeps the
catalog working as installed, lets the publisher preview their own catalog while authoring, and gives
an installation that never overrides the publisher's improvements in the next release. Shipping a
neutral stand-in for identity-bearing resources is then guidance, not mechanism.

**Two problems this shape has, both to be solved in the same phase.** A publisher who _renames_ an
overridable resource silently loses the override: the old address is absent from the new manifest, so
`removeStale` deletes the row, the delete trigger drops its registry entry, and the binding cascades
away. The guard is to refuse the prune; the fix is previous addresses on the wire, sourced from the
publisher's own alias table. And a substitution needs a compatibility contract — fonts get face
coverage and stencils get parameters, but a **theme** replacement missing a block style preset the
publisher's templates name renders plausible, wrong letters in silence, because the renderer resolves
an absent preset to nothing.

Tracked in #918, which carries the current design. D1-D3 and D7 stand as written.

### Errata

Verified against the code while planning; each contradicts something this record assumes.

- **No `schemaVersion` bump.** § Consequences says the additive fields need one. They must not have
  one: `CatalogImportSchemaAction.decide` blocks a SUBSCRIBED import whose source version is below
  current, so a bump would make the Suite refuse every release already published on Exchange. The
  contract's own policy is that additive optional fields need no bump.
- **The URL-subscribed path does not go through `ImportCatalogZip`.** `RegisterCatalog` binds the key
  from the manifest slug and `UpgradeCatalog` installs per resource through `InstallFromCatalog`. So
  "URL subscriptions get all of it for free" is false; each phase needs its own hook on that path.
- **Any new field on a resource detail moves every per-resource fingerprint, once.** The canonical
  form serializes the whole resource object with no allowlist and emits nulls and defaults, and the
  upgrade preview compares a stored baseline against a locally recomputed one. Cosmetic and
  self-healing; the catalog-level fingerprint is the publisher's own stamp and is unaffected.
- **Imported published versions carry no theme snapshot**, so a theme override takes effect on them
  without regenerating anything.
- **Nested commands are savepoints** in the outer transaction; `ImportCatalogZip` is not
  self-managed, the Exchange and URL wrappers are.
- **A duplicate `exchange:` source is reachable today** — a publisher renaming their slug between
  releases creates a second catalog with the same source, because the installer resolves by key.
  D1's source-as-identity closes it.
- **`ThemeRefOverride` already exists** and means a variant overriding its template's default theme.
  Different concept, same word; pick distinct wording in the model.
- **"Make it say who you are" is withdrawn.** Sender name, address and registration details are data:
  the publisher declares `sender` in the data contract and the caller supplies it. The letterhead is
  an overridable stencil. #921 is closed as not planned.

### Since accepted elsewhere

The re-use walk-through's "Deploy" paragraph described a catalog that installs partially. That is no
longer possible: a catalog installs and upgrades as one unit (#850, shipped). Deploying a whole
catalog at once is #920 and is still open.

## Context

Reviewing the first install-from-Exchange release (#915) produced three requests in one sitting:

1. **Install a catalog under a different local key** (#919). If a tenant already has a catalog
   called `invoices`, `acme/invoices` cannot be installed at all.
2. **See and satisfy a catalog's dependencies before installing it** (#917). A catalog that uses a
   theme from another catalog is refused after a full download, with a message that names the
   publisher's local key and offers no way to act on it.
3. **Let a publisher leave things open for the installer** (#918). A generic letterhead catalog
   should render with the installing organisation's own logo — and, as the discussion widened,
   their own theme, their own fonts, and their own header stencil.

These look like three features. They are one problem seen from three sides: **an installed catalog
has no identity of its own, and everything it references is written in the publisher's terms.**

### What is true today

- The local key of an installed catalog _is_ the publisher's manifest slug — one line in
  `ImportCatalogZip` binds it. `catalogs.source_url` already records where a catalog came from
  (`exchange:acme/invoices`), but nothing looks a catalog up by it; every "is this installed?"
  check goes by key.
- A manifest's `dependencies[]` name another catalog by the **publisher's** local key. Outside the
  publisher's tenant that key means nothing: two publishers can both have a `shared` catalog, and
  a publisher's `shared` may itself be something they installed from a third party. Exchange parses
  the list at publish time and drops it (epistola-exchange#6), and its "self-contained catalogs
  only" gate tests the legacy `includes` field instead (epistola-exchange#5), so the guarantee the
  docs describe is not enforced.
- The mirror was never a byte copy of the publisher's content. On the way in,
  `ResourceReferenceSites.qualifyRelative` writes the containing catalog key into every relative
  reference; on the way out, `relativizeOwnCatalog` strips it again. The importer already rewrites
  content deterministically so that it means the same thing here as it did there.
- Every hole the model can express today — placeholders (ADR 0001), stencil parameters (ADR 0002),
  a `themeRef` override, a variant attribute — is filled by **an author, inside catalog content**.
  For a subscribed catalog that content is read-only (`requireCatalogEditable`), and an upgrade
  overwrites it three separate ways: stale-pruning removes anything not in the publisher's
  manifest, `ImportAsset` overwrites bytes, and `ImportTemplates` deletes drafts.
- There is precedent for **consumer state about installed content that lives outside the mirror**:
  `environment_activations` is never pruned by an upgrade, and `PublishToEnvironment` is
  deliberately not gated by `requireCatalogEditable`.
- Relocation (ADR 0020) gave every resource a stable tenant-local identity (`catalog_resources`),
  dissolved the relational coupling between catalogs (dependants hold `*_resource_id`, not another
  catalog's key), and made `ReferenceSiteKind` the single authority on what a reference inside
  content is. There are four kinds: a stencil insertion, an image asset, a theme override, and a
  font family. Three resolve **by address at render time**. The fourth is different: a stencil's
  content is **copied** into the template on insert, re-keyed, and the node keeps `stencilId` and
  `version` as provenance; `StencilContentReplacer` performs the same copy again on upgrade.

### Decision drivers

- One concept, not three features. Each request on its own would add a table, a dialog and a rule
  that the next one has to be reconciled with.
- Whatever the installer decides must **survive an upgrade** without anyone re-doing it.
- Every surface must agree: the editor preview, the PDF, REST, MCP, the resource graph and a
  re-export all have to show the same logo.
- Data stability from 1.0.0 onward: additive schema only.
- The catalog wire format is a GA surface and changes by ADR 0007's rules.
- `catalog` must not depend on `exchange` (enforced in bytecode); the archive digest is verified on
  install, so Exchange must not rewrite an archive.
- Installing must remain possible without a person in the loop — REST and MCP install too.

## Considered options

### For where an installer's choice takes effect

- **A. Resolve at render time.** Keep the publisher's content untouched and have `AssetResolver`
  consult a binding table before falling back to the declared asset. Small and well-fenced for
  assets — and wrong in general. Only the PDF and the server-side preview go through a resolver;
  the editor, REST, MCP, the reference graph and export would all keep showing the publisher's
  value. Themes and fonts would each need their own resolver seam. And stencils cannot be
  late-bound at all: their content is a copy, not a reference.
- **B. Fork on install.** Copy the catalog into an `AUTHORED` one and let the installer edit it.
  Loses upgrades entirely, which is the point of installing rather than importing; no fork command
  exists (#755). It answers a different need and stays available as a future feature.
- **C. Rewrite at import, from durable bindings.** The importer applies the installer's choices to
  the mirror on every install and every upgrade; the bindings, not the mirror, are the truth. The
  mirror is then correct on every surface with no new seam, and the stencil case is the same
  operation the server already performs on upgrade. **Chosen.**

### For how a dependency names another catalog

- **C1. A per-tenant map table** from `(publisher namespace, publisher key)` to local key. Rejected:
  `catalogs.source_url` already _is_ that map, read the other way round. A second table drifts.
- **C2. Exchange resolves bare keys at publish time and rewrites the manifest.** Rejected: the
  installer verifies the archive digest, and an archive should describe itself without a registry.
- **C3. Dependency entries carry a `source`** (`exchange:acme/shared`, or the manifest URL);
  the publisher's Suite fills in what it knows and Exchange validates. **Chosen.**

### For a renamed catalog's public address

- **A catalog alias table** so `invoices/letter` keeps resolving after an install as
  `acme-invoices`. Rejected: `catalog_resource_aliases` is per resource and its key includes the
  resource type, so this would be a new table plus a resolution rule on every catalog-addressed
  surface, to preserve an address the installer knowingly declined. Show provenance instead.

## Decision

**An installed catalog is identified by its source. The importer materialises the mirror by
applying per-installation bindings to the publisher's content, and re-applies them on every
upgrade. Dependencies on the wire name a source, not a key. A publisher can leave a reference open
by giving it a role, and the installer binds a role to a resource of their own.**

Seven decisions follow, and each had a plausible alternative.

### D1. Source is identity

`catalogs.source_url` becomes the identity of an installed catalog. Every "is this already
installed?" lookup — the installer's collision check, the browse page's installed marker, the
upgrade path — goes by source, never by key. Installing a source that is already installed is an
upgrade of that installation, so **one source installs at most once per tenant**. That is also the
answer the asset model needs: a second copy would share asset UUIDs with the first, and unqualified
asset references resolve tenant-globally.

The local key is chosen at install time, defaulting to the publisher's slug, and is only a label.
The catalog page shows provenance ("installed as `acme-invoices` from `acme/invoices`") so that a
publisher's documentation still makes sense.

### D2. The mirror is derived

The stored content of a subscribed catalog is _the publisher's content as installed here_: the
publisher's archive with this installation's bindings applied. The importer applies them on install
and again on every upgrade, inside the import transaction, so an upgrade can never revert a
binding. Nothing about drift detection changes: `installed_fingerprint` and
`installed_resource_fingerprints` are captured from the source manifest and compared source against
source.

Bindings are tenant content. They live in their own tables, are included in tenant backups, and
cascade away with the catalog they belong to.

### D3. Dependencies carry a source

Each entry in a manifest's `dependencies[]` gains an optional `source`, a URI in the same scheme
`catalogs.source_url` already uses:

```json
{ "type": "theme", "catalogKey": "shared", "slug": "base", "source": "exchange:acme/shared" }
```

The publishing Suite fills it: for a subscribed dependency catalog from that catalog's
`source_url`, for an authored one from the tenant's own namespace. The second needs the exporter
to ask "what is this tenant's source identity for catalog `shared`?", which is one query method on
the existing `CatalogReleasePublicationPort` — `catalog` keeps asking, `exchange` keeps answering.
Exchange validates each `source` at publish and refuses a dependency it cannot resolve; that is the
gate epistola-exchange#5 should have been, and the data epistola-exchange#6 exposes.

On install, the importer builds one map from publisher key to local key: the manifest's own slug
maps to the chosen local key, and each dependency maps to the local catalog whose `source_url`
matches. It then rewrites every catalog-key position in the archive through that map — the
embedded sites `ResourceReferenceSites` enumerates, plus the few top-level fields (a template's
default theme, an attribute's code-list binding). A dependency with no matching local catalog is
unmet. On the Exchange path that is known from the release metadata before a byte is downloaded,
and the install dialog can name the missing catalog in Exchange terms and offer to install it
first — sequentially, one transaction each, not as one recursive transaction. An entry with no
`source` (an archive from before this change) is matched by literal key, exactly as today.

### D4. A role is a hole the publisher leaves at a reference site

A publisher **declares** a role on the catalog — a name, the kind of resource it takes, a
description for the person who will fill it — and **uses** it at a reference site by naming it
there. The four site kinds are exactly the four reference kinds relocation already enumerates:

| Site                                                | Kind    | The installer supplies            |
| --------------------------------------------------- | ------- | --------------------------------- |
| `image` node (`props.assetId`)                      | asset   | their logo, a signature, a stamp  |
| `themeRef` override, and a template's default theme | theme   | their house style                 |
| `fontFamily` value in a theme or a document         | font    | their corporate typeface          |
| `stencil` node (`props.stencilId`)                  | stencil | their letterhead header or footer |

Every role has the publisher's own resource as its **default**, which is simply whatever the site
references as shipped. A role is therefore never required: the catalog installs and renders as the
publisher designed it, with or without a person to ask, and REST and MCP installs work unchanged.
Binding is an improvement the installer makes, not a step the install waits on.

Declarations are catalog content — authored in the catalog's settings, exported in the manifest,
mirrored on install, and pruned or replaced by an upgrade like any other resource. Bindings are
installer state and are never touched by an upgrade except to be re-applied. The two lifecycles get
two tables:

- `catalog_roles (tenant_key, catalog_key, role, kind, description, …)` — the declaration.
- `catalog_role_bindings (tenant_key, catalog_key, role, resource_id)` — the installer's choice,
  keyed by relocation's stable identity so that a later move of the bound resource is handled by
  the alias mechanism like every other reference.

Roles are per catalog: every site in the catalog that names `logo` gets the same binding, which is
what "my logo" means to an installer. The exporter checks that every used role is declared and
that the site kind matches the declared kind; the importer refuses an archive that fails either.

A binding can also be made once per tenant, by role name and kind, in
`tenant_role_bindings (tenant_key, role, kind, resource_id)`. Resolution is the catalog's own
binding, then the tenant's, then the publisher's default. Binding `logo` once in tenant settings
then serves every catalog that asks for a `logo`, which turns installing the second, third and
fortieth catalog into a no-touch operation and makes role names a vocabulary worth sharing between
publishers (re-use journey 5 below).

### D5. Applying a binding depends on the kind

Three of the four kinds are references, and binding one is a rewrite of the reference at every site
carrying the role: `assetId` for an asset (tenant-global, and an asset's key is its identity, so
the rewritten value survives any move); `themeId` and `catalogKey` for a theme, on override sites
and on the template's default theme; `slug` and `catalogKey` for a font family. A font binding is
checked for face coverage — the bound family should offer the weights and styles the content uses,
and the result is reported like a relocation blocker rather than silently falling back.

A stencil binding is not a rewrite. The stencil node's content is a copy, so binding a role to a
stencil **re-runs the content replacement** the server already performs on upgrade: the bound
stencil's latest published version is re-keyed into the node in place of the publisher's content,
and the node's `stencilId`, `catalogKey` and `version` become the bound stencil's provenance. The
node's `parameterBindings` are kept, and the same compatibility rule as an upgrade applies — the
bound stencil may not require a parameter the site does not bind. Unbinding is binding to the
default, which is uniform across kinds and needs no archive: the publisher's stencil is a resource
of the catalog or of one of its dependencies, so it is present locally.

### D6. Where the installer fills the hole

The Exchange install dialog shows, from release metadata and before any download, what the catalog
**needs** (dependencies, each marked satisfied or missing) and what it **asks for** (roles, each
with its default). The catalog page shows the same two lists after install, for both the Exchange
and the URL-subscribed paths. Binding and unbinding go through one command, `BindCatalogRole`,
which writes the binding and re-applies it to the stored mirror in one transaction — no re-download
— and is carved out of `requireCatalogEditable` the way `PublishToEnvironment` is. Nothing else
about a subscribed catalog becomes editable.

### D7. Exchange stores and shows what a catalog needs and asks for

Per release, Exchange records the resolved dependency list and the declared roles, and exposes both
on the release and catalog detail endpoints. Dependencies can be backfilled from the manifests it
already stores. This is the read side of epistola-exchange#6.

## Consequences

- **Three issues, one mechanism.** #919 needs D1 and the key map in D3; #917 needs D3 and D7; #918
  needs D4 to D6. None of them needs anything the others do not.
- **No new render-time seam.** The mirror is correct wherever it is read. The price is that a
  subscribed catalog's stored content is no longer identical to the publisher's archive — it
  already was not, and the rule for what the importer may change is now explicit: catalog keys
  through the map, role sites through the bindings, nothing else.
- **URL-subscribed catalogs get all of it** for free, because the same importer serves both paths.
- **The wire format changes additively**: `source` on dependency entries, a `roles[]` declaration
  on the manifest, a `role` on reference holders and on a template's default theme. Per ADR 0007
  that is a new `schemaVersion`; the migration from the previous version is a no-op on content, and
  an older installation refuses the newer archive rather than misreading it.
- **The schema changes additively**: three new tables and a nullable role column on `templates` for
  the default-theme site. All three are in the backup set.
- **Deleting a bound resource is already guarded.** The mirror holds the bound address, so the
  existing in-use scanners for themes, stencils and assets see the reference and refuse the delete
  like any other.
- **Only the publisher can open a hole.** An installer who wants to change something the publisher
  did not leave open still cannot, and that is the same rule stencil parameters follow. Forking
  (option B) remains the answer for that case and is a separate decision.
- **The local key can differ from the publisher's docs.** Provenance on the catalog page, and the
  source in every dependency, are what keep that legible; no alias table.
- **Installing one source twice is not supported.** If that is ever wanted, it needs its own
  decision, because asset identities are tenant-global.
- **Exchange gets a real gate.** "Self-contained or refused" becomes "every dependency resolvable
  or refused", which is the guarantee installers actually need.

## What re-use looks like for the installer

Sharing a catalog is only worth anything if the installer can actually use it. Five ways of
re-using an installed catalog were walked through against the code as it stands, to check that the
decisions above serve them and to be honest about what they leave.

### 1. Use it as shipped

A sector body publishes standard letters; a municipality installs them and generates from its case
system.

- **Find and evaluate.** Browse and search exist. Exchange shows the resource list and image
  thumbnails; it shows **no rendered example of a template and no data contract**, although both
  are in the archive — every template version ships its data model and examples. A person choosing
  between two letter packs needs to see a letter. That is an Exchange feature, outside this ADR.
- **Install and bind.** D1, D3 and D6.
- **Deploy.** `ImportCatalogZip` hands `ImportTemplates` an empty `publishTo`, so an installed
  template is published but **deployed to no environment**, and an upgrade leaves every existing
  deployment on the previous release's version. That is deliberate and stays so: deploying is a
  human decision, separate from installing content, as it is for stencil upgrades. What is missing
  is making that decision once for a whole catalog rather than per template, variant and
  environment, and being offered it from the install and upgrade dialogs — #920.
- **Generate.** The REST request names catalog, template, variant (or selection attributes) and
  environment. With D1 the catalog key is whatever the installer chose; the provenance on the
  catalog page is what an integrator copies it from.
- **Upgrade.** Bindings survive (D2). A change to a template's **data contract** is the change an
  integrator fears, and neither path surfaces it: the URL path diffs resources, the Exchange path
  shows release metadata. A contract diff needs the archive, so it is the upgrade preview ADR 0021
  declined to build, with a sharper reason to build it.

### 2. Build on it as a library

A design agency publishes a house style — theme, fonts, header and footer stencils — and the
installer authors their own templates on top.

This works today and needs nothing from this ADR to keep working. The stencil and theme pickers
are tenant-wide; an inserted stencil is a pinned copy that does not change under the author; the
editor shows the upgrade indicator when the library moves on, and the bulk upgrade page applies it
to drafts. A library release that **removes** a stencil still in use is refused as a whole
(`CatalogUpgradeConflictException` names each use), which is correct, and the installer's way out
is to detach each instance first.

What the ADR adds is that the relationship becomes publishable: when the installer publishes a
catalog of their own, its dependency on the library carries `exchange:agency/house-style` (D3), so
a third party can install both under whatever keys they like.

### 3. Derive from it

The shared letter is almost right; the installer wants one extra paragraph, or a variant for a case
the publisher did not foresee.

This is the most common request and **the one nothing serves**: a subscribed template is
read-only, variants cannot be added, and there is no copy or fork command (#755). Roles do not
help — they change what a site points at, not what the publisher wrote. Option B was rejected as
_the_ mechanism, not as a feature. **"Copy to my catalog"** is a one-resource import into an
authored catalog, and the relativise-and-requalify machinery means the copy keeps pointing at the
library's theme, stencils and assets rather than duplicating them. Recording where a copy came from
(source, release, resource) is what would let the editor say "the original changed since you copied
this", the way it already does for stencils. That is a decision of its own and should follow this
one.

### 4. Make it say who you are

Every letter carries the sender's name, address, registration number and a signature line. A
generic catalog cannot know them.

Today a template has `sys.pages.*` and `sys.render.time` and nothing else that is not request
data, so a generic letter either hardcodes the publisher's details — the demo catalog does — or
pushes "sender" into its data contract for every caller to supply on every call. These are
**values, not resources, and they are tenant-wide** — one organisation name, however many
catalogs — so they are deliberately not roles. A tenant profile exposed to templates
(`sys.tenant.name`, `sys.tenant.address`, …), edited once in tenant settings, is the sibling
decision to this one and the second half of "generic catalogs actually work". Roles supply the
logo; the profile supplies the name under it.

### 5. One catalog, many tenants

A holding installs the same catalog into each subsidiary's tenant; a consultancy into each
client's. Per-tenant bindings and per-tenant local keys make this work, but "install, then bind
three roles" repeated across forty tenants is forty times the same dialog. Hence the tenant-level
binding in D4: bind `logo` once per tenant and every catalog that asks for `logo` is served. Role
names thereby become a small shared vocabulary — `logo`, `house-style`, `letterhead` — that
Exchange can recommend to publishers, which is what makes a no-touch install possible.

### What the walk-through changed

The tenant-level binding in D4, and three things named as out of scope so they are not mistaken
for covered: deploying a whole catalog at once (#920), a copy command with provenance, and a tenant
profile for values.

## Rollout

The order matters more than the size of any step.

1. **Exchange** — fix the gate (epistola-exchange#5), store and expose dependencies
   (epistola-exchange#6). Independent of everything below.
2. **Contract** — `source` on dependency entries, `roles[]` on the manifest, `role` on reference
   holders and `TemplateResource`. One `schemaVersion` bump.
3. **Suite: identity and the key map** (D1, D3) — source-keyed lookups, a local key field in the
   install dialog, the importer's rewrite. Ships #919 in full and the mapping half of #917.
4. **Suite: dependencies in the dialog** (D6, D7) — satisfied/missing, early refusal, "install
   `acme/shared` first". Finishes #917.
5. **Suite: roles** (D4 to D6) — declarations in catalog settings, `role` in the editor's
   inspectors, the three tables, `BindCatalogRole`, the dialog and catalog-page surfaces. Ships #918
   for all four kinds. Assets and themes first; fonts and stencils carry the extra checks in D5.
6. **Demo catalog** — a generic letterhead catalog that declares a logo, a theme and a header role,
   installed into a second tenant with all three bound. It is the feature's only honest
   demonstration and the fixture every test above wants.

## Open questions

- **Property name.** `role` is used here; it must not collide with anything the editor already
  puts on `props`, and a `themeRef` or `fontFamily` object gains the same field.
- **Should Exchange verify that an `exchange:` source exists at publish time?** Probably yes; an
  `https:` source it cannot, and should record as given.
- **Fonts: warn or refuse on incomplete face coverage?** Determinism (`docs/fonts.md`) argues for
  refusing; a publisher who used one weight argues for warning.
- **Does a role want a preview?** Rendering a template with a candidate binding before committing
  it is the same problem as the upgrade preview ADR 0021 declined to build.

## References

- #917 Show a catalog's dependencies before installing, and help satisfy them
- #918 Let a published catalog leave an asset for the installer to supply
- #919 Install a catalog under a different local key
- #920 Deploy many templates at once, offered on install and upgrade
- #921 Give templates the organisation's own details through a tenant profile
- #922 Copy a shared template into a catalog of your own, remembering where it came from
- #923 Say when a catalog upgrade changes a template's data contract
- #850 Catalogs as cohesive install units; #755 read-only editor and the absence of a fork command
- epistola-exchange#5 Self-containment gate tests the wrong manifest field;
  epistola-exchange#6 Store and expose a catalog release's dependencies;
  epistola-exchange#7 Show a rendered example and the data contract for every template
- [`docs/catalog-exchange-installation.md`](../catalog-exchange-installation.md),
  [`docs/resource-reference-graph.md`](../resource-reference-graph.md),
  [`docs/stencils.md`](../stencils.md) (content replacement), [`docs/fonts.md`](../fonts.md)
- `ResourceReferenceSites` — the single authority on embedded references and the walker every
  rewrite in this ADR goes through
