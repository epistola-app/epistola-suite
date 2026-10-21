# Catalog Versioning & Upgrading

How catalogs declare a version, how "is this a new version?" is decided, and
how that flows across the web UI, REST, MCP, catalog exchange and the bundled
catalogs. Companion to [`docs/exchange/README.md`](exchange/README.md).

> This covers the **release version + content fingerprint** axis. For how it
> relates to the other version concepts (the wire-format `schemaVersion`, and
> the template, contract and stencil versions 2.0 removed), see
> [version-axes.md](version-axes.md).

## Why

Authored catalogs had no real version: the ZIP export stamped
`LocalDate.now()`, and every "changed?" decision (`DemoLoader`,
`SystemCatalogBootstrap`, `UpgradeCatalog`) was an exact **string equality** on
`installed_release_version` (a SUBSCRIBED-only column). An author could not say
"this is now a new version" with any meaning, and re-export produced a
different "version" even when nothing changed (or the same one when it did).

## Model

A catalog is **one row per `(tenant_key, slug)`**. A subscribed catalog has one live resource set,
its installed release; an authored catalog has one per open draft (see
[drafts](#catalog-versions-and-drafts)).

> This describes what ships today. [ADR 0026 §6](adr/0026-revisions-releases-and-the-working-copy.md)
> decides otherwise for the next major: an installation will hold and resolve several releases of one
> catalog; a request that names no version will resolve to the release its environment is on, or the
> latest outside one; and a subscribed catalog will not be upgraded at all — another release of it is
> installed alongside, leaving the one already there in place.

- **AUTHORED (publisher side)** — one live, editable copy per open draft. Cutting a
  release records an **immutable boundary** in `catalog_releases`
  (author-set SemVer + content fingerprint + notes + a manifest snapshot), one
  `release_entries` row per resource naming the revision that holds its content
  and the digest it fingerprinted to, and
  advances the `catalogs.released_version` / `released_fingerprint` /
  `released_at` pointer. `catalog_releases` is AUTHORED release **history**
  (changelog), not parallel installs and **not** the consumer upgrade-diff
  baseline (a SUBSCRIBED consumer's DB has no `catalog_releases` rows).
- **SUBSCRIBED (consumer side)** — exactly one installed state.
  `installed_release_version` + `installed_fingerprint` record which release is
  installed; `installed_resource_fingerprints` (JSONB) records the per-resource
  source-side digests of that release — the Phase 2 upgrade-diff baseline.
  `UpgradeCatalog` replaces resources **in place**.

`installed_*` (SUBSCRIBED) and `released_*` / `catalog_releases` (AUTHORED) are
orthogonal — `CatalogType` is exclusive, so only one applies to a given
catalog. An authored `1.4.0` exported and subscribed elsewhere flows its
version + fingerprint through the manifest into the subscriber's
`installed_release_version` / `installed_fingerprint`, so values stay coherent
end to end.

## Who may change a catalog, and what receiving a release does

ADR 0032 replaces the catalog types; until the code and this page drop the words, `AUTHORED` means
made here and `SUBSCRIBED` received from a source or an archive.

- **Every catalog has a next version**, the draft its browsable content lives in. A received
  catalog's next version starts on the release it installed (`base_version`); there is no installed
  copy any more (`V20261021063432`).
- **A catalog can be changed** when the installation allows editing
  (`epistola.catalog.editing.enabled`, default `true`) and the suite does not supply it (a
  `classpath:` source: the system and demo catalogs). `GetCatalogEditability` says which, and
  `requireCatalogEditable` refuses with the reason (`CatalogReadOnlyException.reason`). With editing
  off the installation is **run-only**: it receives, deploys and rolls back releases, and nothing in a
  catalog is started, changed, merged or released. Where releases come from does not matter: a
  received catalog can be changed and released like one made here.
- **Receiving a newer release** (an import, an Exchange install, an upgrade from a source) records it
  and, when the next version has no changes of its own, moves the next version to it (a
  fast-forward). With changes of its own (`NextVersionChanges`), the release is recorded beside the
  others and the next version is behind it, to be merged; an upgrade from a source is refused
  (`CatalogHasLocalChangesException`), since it would discard them. Changes are measured against what
  the next version held when the release was put in place (`catalog_versions.base_fingerprints`, set
  by `RecordInstalledRelease`), else against its base release.
- **The web UI and MCP decide by this, not by type.** A page offers drafts, edit controls and
  Publish for a catalog that can be changed here, received or not, and shows a read-only catalog's
  reason ("Supplied by Epistola", "This installation only runs releases", the reason's `label`) with
  the release its content is on. What depends on a source stays keyed on having one
  (`Catalog.hasSource`): checking for and installing newer releases. Lists ask once per page
  (`ListCatalogEditability`), and a catalog's version is its highest release, released here or
  received (`Catalog.highestReleaseVersion`). The web UI's import no longer asks for a type: it
  receives the release, creating a catalog it does not have as received.

## Catalog versions and drafts

A catalog consists of **versions** (contract ADR 0002): released ones, kept in
`catalog_releases` with their retained content, and the versions whose content is in the content
tables, identified by `catalog_versions`: the open **drafts** of an authored catalog, and the one
**installed** release of a subscribed catalog. A draft has **no number until it is released**; only
releases (and an installed release) are numbered.

- **Every draft is the same; one is the next version.** Any draft can be released (contract ADR
  0002). Every authored catalog marks one draft the **next version**
  (`catalog_versions.is_next`, one per catalog): the draft everyday work goes into and the one a
  caller that names no version means. The mark is a suite default, not a kind of draft. Every other
  draft is named (`catalog_versions.name`, unique per catalog ignoring case): a redesign, a fix to an
  older line, work tried by deploying it to an environment. A version is identified by its
  **version id** (`version_key`, eight characters such as `k7m2x9qa`, from `epistola_version_key()`),
  which a release keeps from the draft it was released from; `next` names the next version.
- **Content belongs to a version.** Every content row (`themes`, `document_templates`,
  `template_variants`, `variant_attribute_definitions`, `code_lists`, `code_list_entries`, `assets`,
  `fonts`, `font_variants`, `stencils`) is keyed `(tenant_key, version_id, resource_id)`; its address
  is unique per version. Ready marks (`resource_ready_marks`) and dependency declarations
  (`catalog_dependencies`) are per version too.
- **Identity is shared.** `catalog_resources` holds one identity per resource, used by every version
  that holds a row of it, and every reference to a resource (a template's theme, an attribute's code
  list, a font face's asset, the tenant's default theme, quality findings, load-test runs, history)
  names the identity. A row inserted into another version at the same address adopts its identity;
  deleting a row deletes the identity only when no version holds the resource any more. A reference
  resolves in the version being read.
- **Starting a draft.** `CreateCatalogDraft` starts one, named, as an independent copy: from a
  release (its number or version id; the default is the highest release), rebuilt as its archive and
  imported, which is its base; or from a draft (its version id, or `next`), unreleased changes
  included, on that draft's base. `RenameCatalogDraft` renames it.
- **Discarding.** `DeleteCatalogDraft` discards a draft and everything in it. Another draft is gone;
  the **next version** starts again as a copy of the latest release (`NextVersionOpener`), so everyday
  work always has somewhere to go. It is refused while a draft of another catalog names it as a dependency
  (`CatalogDraftInUseException`), and (`CatalogVersionHeldException`, REST
  `CATALOG_VERSION_HELD`, 409, `heldBy`) while the draft alone holds a resource something outside it
  still references: the tenant's house theme, a code list an attribute of another version or
  catalog binds, or a theme a template of another version or catalog uses. Discarding would delete the
  resource's identity, and those references would fail as a foreign-key violation or, for a template's
  theme, be cleared silently. The refusal names each holder, e.g. `the tenant's default theme (theme
  letters/seasonal)`.
- **Theme settings** (contract ADR 0007). A version carries its default theme and the presets it
  declares (`catalog_versions.default_theme_resource_id`, `presets`). A release freezes both in its
  manifest's `catalog` and renders with them; new parallel work copies them from the version it starts
  from, and an import takes them from the archive.
- **Behind.** A draft is _behind_ a release it does not contain: (a) one above its base, or (b) one
  below it released after the draft started, a fix to an older line (`CatalogDrafts.behind`). It
  lists each with what it changed against its own base. The next version continues on each release
  it is released as, so in the ordinary course it is behind only fixes to older lines; another draft
  is behind every release since it started. Releasing a draft that is behind is refused
  (`CATALOG_VERSION_BEHIND`, 409) until `acknowledgedBehind` names every one. Behind is computed only
  when asked for (`ListCatalogVersions(withBehind)`, `GetCatalogDraft(withBehind)`), since it reads
  every release's content.
- **Merging** (alpha, ADR 0032). A draft takes in a release it is behind with
  `MergeReleaseIntoDraft`; `PreviewDraftMerge` says first what that does. Per resource, from three
  fingerprints the suite already keeps (the draft's base and the release from `release_entries`, the
  draft from its content): what only the release changed is taken (written as the release has it, or
  removed), what only the draft changed stays, and a resource both changed differently is a conflict
  the person resolves to one side. Taken resources are marked ready, since they are released content;
  the draft's default theme, presets and dependency declarations stay its own. A release that removes
  something the draft still uses is refused (`MergeRefusedException`), as is one the draft already
  contains. The draft records the release (`catalog_versions.merged_releases`), which `behind` then
  leaves out; its base does not move. Merging inside one resource is later work.
- **A release records its base** (`catalog_releases.base_version`, `V20261018200200`): the release
  its draft started from, which `getCatalogRelease` reports as a released release's `base` and which
  `behind` measures its changes against. It is null for a catalog's first release, a release installed
  from elsewhere (`RecordInstalledRelease`; the archive does not carry the publisher's base), and every
  release cut before the column. Those fall back to the release below them.
- **Releasing** (`ReleaseCatalogVersion`, contract `releaseCatalogDraft`) releases any draft,
  numbered as asked or, without a number, as its changes need against its base
  (`AssessDraftNumber`, contract `previewCatalogDraftRelease`: `base`, `changeKind`, `needed`,
  `suggested`): breaking changes the next major, additions the next minor, anything else the next
  patch; a draft with no base starts at `1.0.0`. The suggestion is the next free number of that kind,
  so a fix to 1.2.x on a catalog that has 1.2.1 and 2.0.0 is 1.2.2. A number must be free, above the
  base and never lower than the changes need (`CatalogReleaseVersionException`); the author may raise
  it, to mark a major on purpose. The release takes the draft's version id. Releasing the next version
  **continues** it: the same draft under a new version id, its content the release's, its base the new
  release. Releasing another draft removes it: environments following it stop (recorded as
  released), and another catalog's draft that named it as a dependency now pins the release.
  Other drafts are untouched and may now be behind it (`draftsBehind`, by name). The 2.0 upgrade
  releases what was published before 2.0 under the next patch of the latest release, as its draft was
  numbered then, and is not held to the change rule.
- **What a draft's changes need** (`ChangeKindEvaluator`, ADR 0001's rules for a hotfix) compares the
  draft's content with a release's retained content. Breaking, needing a major: a resource removed; a
  data contract that no longer accepts every input the base accepted (`SchemaCompatibilityChecker`),
  or one added where there was none; a variant removed or its attributes changed (a request selecting
  by them may get another variant); a stencil placeholder removed, a parameter an instance must now
  bind, or one retyped (the fit rule of `StencilInstances.placeholders` / `mustBind`, plus type); an
  attribute value no longer allowed, a restriction where there was none, or another code-list binding;
  a code-list code removed; a dependency's lock moved to a version that breaks a resource this catalog
  uses (`UseCompatibility`: compatibility is judged per use, so a dependency's new major that breaks
  only what is not used here is a fix, and a patch that breaks something used is breaking). Additive,
  needing at least a minor: a resource, variant, placeholder or optional parameter added; a contract
  widened or dropped; a parameter no longer required or removed (a binding of it is ignored); values
  allowed or a restriction lifted; a code-list entry added or its label, order or hidden flag changed;
  a dependency added (an installation brings it along). Everything else is a fix, including a
  dependency removed: it changes nothing others rely on. The same rules
  decide whether a newer release of a catalog this one uses fits what it uses (`GetCatalogBorrowing`).
- **Relocation** moves the next version's rows, so it is refused (`several-drafts`) while the source
  or target catalog has another draft.

### Which version a statement means

Every statement on content names its version through the helpers in
[`CatalogSql.kt`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogSql.kt):
`versionAt(t, c)` and `inCurrentVersion(alias)`, both over the view `catalog_current_versions`, or
`versionNamed(t, c, v)` for an installed release by number; `ContentVersionSqlTest` fails a statement
that names none. The view answers, per catalog, the version the caller means:

1. the version the transaction **targets**, when it is a version of that catalog;
2. else a subscribed catalog's installed release;
3. else the authored catalog's **next version**. Another draft is reached only by targeting it.

A command or query that addresses one draft implements
[`CatalogVersionScoped`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/versions/CatalogVersionScoped.kt)
and carries a `CatalogVersionRef` (tenant, catalog, `DraftRef`: `Next` or `Id`). `SpringMediator`
resolves it before the handler runs (an unknown draft is `CatalogVersionNotFoundException`), runs a
query that names one in a read-only transaction, and `CatalogVersionTargeting` sets the PostgreSQL
setting `epistola.content_version` to the draft's `version_id`, transaction-locally. Handlers written
for "the" version of a catalog then work on that draft unchanged, and every message they dispatch
inherits it; the caller's target is restored when the message ends. A message that names no version
works on the next version. A self-managed command (`RefreshCodeList`) applies the target in each
transaction it opens.

The REST API's `…/releases/{release}/…` paths take `next`, a draft's release id or a release number
(`ResolveCatalogVersion`), pass the draft they name to every message they dispatch
(`ResolvedCatalogVersion.ref`), and read a released version from its retained content.

### Choosing a draft in the UI

The web UI names a draft other than the next version in the catalog's own path segment:
`letters@<version id>` is that draft of `letters`, plain `letters` its next version (`CatalogSegment` in `epistola-web`; `@` cannot occur in
a catalog key). So `/tenants/acme/catalogs/letters@k7m2x9qa/templates/invoice` is the invoice in that work, and the
same works for every per-catalog and per-resource route (catalog page, themes, stencils, code lists,
fonts, images, the template editor and its preview). List pages take it as their `catalog` filter
(`/catalogs/letters@k7m2x9qa/attributes`), and their create dialogs then create there.

- **Why the URL, not the session.** A link to a draft is shareable and survives reloads, two tabs
  can work in two drafts, and every link, HTMX request and editor call built from the segment stays
  on the draft without any of them knowing about drafts. The editor's own requests read the segment
  from the editor config (`catalogSegment`) and use it for its own catalog's URLs; requests across
  catalogs (stencil search, image content) name the draft as `in=letters@…`, so that catalog is read
  from the draft and every other from its next version.
- **Every message names it.** Handlers pass the segment's draft as `catalogVersion` to every command
  and query they dispatch, including the UI-only ones (`GetEditorContext`, `ListTemplateSummaries`,
  `GetThemeUsagePage`, `ResolveTemplateTheme`, `PreviewVariant`, `BrowseCatalog`, …).
- **The context bar** (read model `CatalogDraftBar`, [`workspace-shell.md`](workspace-shell.md)) says
  which draft a page works in: "Next version", or another draft by its name with a strip saying it is
  kept apart (the UI calls such a draft _parallel work_). Its version menu lists the next version and each piece of parallel work, and offers
  _Start parallel work…_ (`catalogs/drafts.html`: a name, and a published version or the next version
  to start from).
- **Publishing** is offered for every draft, numbered from its changes against its base; the release
  dialog lists the releases it is behind, with their changes, and asks to confirm each
  (`acknowledgedBehind`). _Discard_ on the next version throws its changes away; on another draft it
  removes it.
- A route naming a draft the catalog does not have is a 404; a number names no draft.

Over MCP the content tools take an optional `catalogVersion` argument (`next` or a version id), and
the version tools list versions, start, rename and discard drafts, preview a draft's release number,
release a draft and make one the next version (see [`mcp.md`](mcp.md)).

## Versioning scheme: SemVer + content fingerprint

- **SemVer** (`MAJOR.MINOR.PATCH`) is author-controlled, recorded per release.
  Implemented by the dependency-free
  [`SemVer`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/SemVer.kt)
  value type (parse / compare / bump). Releases must strictly increase.
- **Content fingerprint** is a deterministic lowercase-hex SHA-256 of the
  catalog's canonical content, computed by
  [`CatalogCanonicalizer`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogCanonicalizer.kt)
  (wired by `CatalogFingerprintService`). It identifies _content_ independently
  of the version label — "is this actually new?" is a fingerprint comparison,
  not a string compare.

### Fingerprint algorithm

Hashes, in order: the complete portable catalog identity and discovery metadata
(`slug`, name, description, qualified attributes, case-preserving keywords,
ordered presentation assets, and license); each resource
sorted by `"$type/$slug"`, rendered as `key  <canonical resource JSON>  <asset
bytes SHA-256 | "" | "MISSING">`; then a sorted, `;`-joined dependencies line.
Resource JSON is canonical: the serialized `ResourceDetail` is parsed (floats
as `BigDecimal`), the `resource` subtree taken, every object's keys recursively
sorted (array order preserved — arrays are ordered content), then compact-written.
Excluded by construction: `release.*`, `schemaVersion`, every `updatedAt`,
`detailUrl`. Asset _binary bytes_ are folded in via their own SHA-256 (mirrors
the font-family fingerprint), so swapping an image flips the fingerprint even
though the `AssetResource` JSON is unchanged.

**Wire-format migration is fingerprint-transparent.** Because `schemaVersion` is
excluded, the whole-catalog import migration ([ADR 0007](adr/0007-catalog-wire-format-migrations.md),
[`docs/exchange/`](exchange/README.md#wire-format-version-gate)) — which upgrades
an older payload's shape and re-stamps `schemaVersion` to the current catalog
version — is **preserved verbatim**: `release.fingerprint` / `release.version` are never
touched or recomputed during migration, so a migrated import compares as the same
source release and the idempotent-re-import SKIP still holds.

**One definition (invariant).** There is a single canonicalization, run over
the **serialized resource-detail JSON** (the exact wire form). The value
stamped into an exported manifest / stored on release and the value any
consumer recomputes from those detail bytes via
[`fingerprintFromSource`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogCanonicalizer.kt)
**agree by construction** — there is no separate "typed object" path that could
diverge for `Float`/`Double` fields (numbers never round-trip through
`Double`/`Float.toString`, whose algorithm changed across JDKs — JDK-4511638).
Guarded by `CatalogFingerprintEquivalenceTest`. The export ZIP and the
fingerprint are built from the **same**
[`CatalogContentBuilder`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogContentBuilder.kt),
so "the bytes you export" ≡ "the bytes you fingerprint".

### Portable discovery metadata

Catalog wire v6 stores discovery metadata alongside catalog identity:

- qualified attribute assignments (`catalog`, `key`, `value`);
- exact-case keywords;
- an icon and ordered image gallery referencing assets in the same catalog;
- license name, optional SPDX expression, absolute HTTP(S) URL, and copyright.

AUTHORED catalogs edit these fields from the catalog browse page. SUBSCRIBED
catalogs show source metadata read-only. URL registration, URL upgrade, ZIP
import, export, release snapshots, and tenant snapshots preserve the same typed
value. A metadata-only publisher edit appears in the upgrade preview and can be
applied without fabricating a resource change.

Presentation media is deliberately **installed-only**. Epistola never proxies
or auto-downloads an uninstalled source asset just to render catalog chrome.
The browse page shows an unavailable placeholder; local exports and snapshots
omit missing icon references and filter missing gallery entries while preserving
the order of installed entries. The full source metadata remains stored so the
presentation appears as assets are installed later.

## Size a catalog to be adopted whole

A consumer installs a catalog whole and upgrades it whole; there is no way to take a subset
(see [the installation guide](catalog-exchange-installation.md#why-a-catalog-is-one-unit)). So the
catalog boundary is the adoption boundary, and drawing it is an authoring decision, not something to
leave to each consumer.

Keep a catalog focused on resources that belong together and are adopted together. When two groups
of resources have independent audiences or release rhythms — a house style versus a set of letters,
say — publish them as two catalogs and declare the dependency between them. That is cheaper for
everyone than one large catalog nobody wants all of.

## Declared dependencies

A catalog that uses another catalog's resources declares that catalog once (wire v8
`catalogDependencies`, contract ADR 0003): by registry, namespace and key, **pinned to one exact
release** of it (`version`), not a range. A draft may instead name a **draft** of that catalog
(`releaseId`; column `dependency_version_id`), to build on work not released yet; it renders with that
draft. `UpdateCatalogDependencies` (`TEMPLATE_EDIT`) replaces the declarations of an authored catalog;
each must name a catalog the tenant has, not itself, not twice, and a release it has with a `v5:`
fingerprint (a release cut before 2.0 has none) or a draft it has. The pin stays when the dependency
releases again; `UpgradeCatalogDependencies` moves every pin to the highest release, the way a
library upgrade does, and leaves a declaration naming a draft as it is (the UI's _Use the latest
versions_). Renaming the dependency's namespace carries the declarations that name it.

**Declared as it is used.** A write that starts using another catalog declares it
(`UsedCatalogDeclarer`), on every surface: a variant's stencils, images and fonts, a template's theme,
a theme's fonts, a stencil's content, an attribute's code list. An authored catalog is declared by
naming its **next version**, which is what the draft then renders it from; a subscribed one by pinning
its installed release. A declaration already there is left as the author set it.

**Catalogs never use each other.** A declaration naming a catalog that already uses this one,
directly or through others, is refused, and so is the write that would add it, in the same
transaction (`CatalogDependencyStore.replace`, `ValidationException` with
`CATALOG_DEPENDENCY_INVALID`, naming the way round: `'letters' uses 'shared' uses 'base'`). It is
judged by what every other version declares: each draft of each catalog, parallel work included, and
each subscribed catalog's installed version. 2.0 could not release such catalogs, since each would
have to pin the other's release first, and installing, restoring a snapshot and the upgrade from 1.x
all order catalogs by what they use. What two catalogs share goes into one of them, or into a catalog
they both use. Only a declaration being added is judged, so an edit elsewhere in a catalog is not held
to a state from before this rule.

**A draft renders as it is declared** (`DraftDependencyView`): another catalog's resources come from
the release its declaration pins, or live from the draft it names; a catalog only a pinned release
depends on comes from the release that one pinned, nearest first. Stencil expansion, theme, fonts and
images all follow it, in the editor, previews and documents generated from a draft alike, so what a
draft shows is what its release is cut with. A catalog nothing declares (one the tenant lacks, or
left undeclared by hand) is read live from its current version.

**Releasing** is cut with the pins. A declaration that names a draft with nothing of its own to
release (no resource changed against its base, no draft named, the same pins as its base) pins that
base instead, with or without asking. Any other named draft must be released first:
`releaseDependencies` releases each such draft first, recursively, each numbered as its changes need;
without it the release is refused (`CatalogDependenciesUnreleasedException`, REST
`release-dependencies-unreleased`, naming each). Two catalogs naming each other's drafts cannot
arise, since catalogs never use each other (above); the release still refuses them
(`CATALOG_DEPENDENCY_INVALID`) as a last guard. The web UI's _Publish_ always releases with
`releaseDependencies`, lists the catalogs it publishes along with their numbers, and marks their
changes ready as it marks its own. A pinned release that is gone refuses the release
(`CATALOG_DEPENDENCY_UNRESOLVED`), and so does a resource reference into a catalog the catalog does
not declare. The release records each declaration with its pin (`version` and
`releaseFingerprint`) in its manifest and in `catalog_release_dependencies`
(`ListReleaseDependencies`).

**Rendering** a release reads another catalog from the release it pinned, whatever was released or
deployed since; a catalog only a dependency uses comes from the release that dependency pinned,
nearest first. A pinned release the tenant no longer has, or has with another fingerprint, fails the
render. Only a release cut before 2.0, which pinned nothing, still reads other catalogs from the
environment's deployment or their latest release.

**Deploying** follows the pins too: a deployed release uses every release it is pinned to, direct and
transitive, implicitly — they render from the tenant's copy, so the environment need not serve them,
and they are not deployed. Deploying them as well is a choice (`includeDependencies`). Only a pinned
release nothing could render from refuses the deployment (`ReleaseDependenciesMissingException`,
REST `release-dependencies-missing`); see [Environment Management](epistola.md#environment-management).

**Seeing them.** `GetCatalogDependencyGraph` returns every catalog of the tenant and its current
version's declarations; the catalog list's _Dependencies_ page draws it (`CatalogGraphView`, SVG
rendered on the server), and a catalog's Dependencies page draws it narrowed to that catalog: what it
uses and what uses it, transitively.

**Retention** follows the pins: a release that a release of another catalog is pinned to cannot be
deleted (`ReleaseInUseException` names the pinning releases), and neither can its catalog be deleted.
Deleting the pinning release first frees it. A release always holds its content: deleting it is the
only way to reclaim what it holds.

**Moving** a resource into another catalog declares what the move creates: every authored catalog
whose content now references a catalog it did not declare gets a declaration pinned to that
catalog's highest release, or naming its next version when it has none (`MissingDependencyDeclarer`).

**Installing** keeps the archive's pins: a subscribed catalog's declarations and the pins of its
installed release are the publisher's, and so are an imported authored release's. Every pinned
release must be present, carried in the archive or installed already
(`CatalogImportDependenciesMissingException`, REST `release-dependencies-missing`).

**Upgrading from 1.x** declares what 1.x left undeclared, and cuts each catalog's first release:
1.x had no declarations, and no 1.x release kept its content. The upgrade queues every authored
catalog that exists then (`catalog_upgrade_queue`), and a background task (`CatalogUpgradeRunner`)
gives each a declaration for every catalog it references, pinned to that catalog's highest
release (as a move does), and pins every declaration still unpinned (`pinUnpinned`), then releases it from what was last published before 2.0, numbered the
patch after its highest 1.x release, or 1.0.0. The upgrade migration records that number in the queue
(`continues_after`) and removes the 1.x releases, which kept no content, so each catalog starts 2.0
with that one release and its next version on top. A template whose default variant was never published, and a stencil never published, are left
out of that release; unpublished work stays in the draft. Nothing is deployed: operators deploy the
first releases. Catalogs are taken in rounds, so one is released after the catalogs it depends on;
one that cannot be released yet stays queued with the reason (`last_error`) and is retried. A catalog
created after the upgrade is never queued. A subscribed catalog with an installed release is queued too, and gets
that release recorded as one it can render from; a legacy fingerprint is replaced by the qualified one
of the installed content, so dependents can pin it.

## Ready marks

Templates, stencils and every other resource are edited in place: a template variant has one
working model (`template_variants.template_model`), a template one data contract
(`document_templates.data_model` / `data_examples`). There are no template or contract versions; the
catalog release is the only version (contract ADR 0002).

What replaces publishing is a **ready mark**: a record that a resource's current content is
reviewed. `MarkVariantReady`, `MarkTemplateReady` (every variant of a template), `MarkStencilReady`
and `MarkCatalogReady` (every changed resource of the catalog) set them; MCP has a tool for each.

- A mark stores the content digest the database computes for the resource (view
  `resource_content_digests`, table `resource_ready_marks`). It holds while the digest is unchanged,
  so any edit makes it stale without anything clearing it.
- A template is marked per variant. A variant's readiness covers its template's shared fields and
  data contract and which variants exist, so editing the contract or adding a variant makes every
  variant of the template stale.
- Marking a variant ready runs what publishing used to: the model must be publishable and every
  stencil instance must fit its stencil. Marking a stencil ready checks the stencil's own instances.
  Other resources are marked without a check.
- A subscribed catalog is read-only: nothing in it can be marked.
- An authored ZIP import marks everything it imported ready — it is released content.

`ReleaseCatalogVersion` refuses with `CATALOG_RESOURCES_NOT_READY` while any resource that is new or
modified since the last release — of any type — is not ready. `GetCatalogResourceChanges` (below)
reports each resource's state and whether it is `ready`.

The 2.0 upgrade (`V20261018200600__core_working_copy.sql`) gives each variant its
draft model, else its latest published, else its latest archived; each template its draft contract,
else its latest published. What was publishable before is marked ready: a variant with no unfinished
draft, a stencil without a draft, and every other resource. `template_versions` and
`contract_versions` stay as history nobody reads.

## Cutting a release (AUTHORED)

An authored catalog's page has a **Next release** section (`catalogs/next-release.html`): every
resource that changed since the draft's base with its state and whether it is ready, **Mark all
ready**, and the declared **Dependencies** with their locks (edited in a dialog: the catalogs it uses,
each tied to its latest published version, which a newer one replaces once it fits what is used;
**Resolve to latest** moves the locks). The next version has no number until it is published; the
release dialog picks it from the kind of change and may raise it under Advanced. **Release** is offered only while nothing blocks it; otherwise the
section says which resources are not ready or which dependencies match no release, and where to fix
it. The page and the release dialog gate on the same read model, `CatalogReleaseReadiness`. The
dialog (also reachable from the catalog list) pre-fills the draft's number, offers Patch / Minor /
Major quick-picks and release notes, and names what this release will add, update and drop. It dispatches
[`ReleaseCatalogVersion`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/commands/ReleaseCatalogVersion.kt):
AUTHORED-only, refuses while a changed resource is not [ready](#ready-marks),
parses + monotonic-checks the SemVer, computes the fingerprint,
inserts the `catalog_releases` row (with the manifest snapshot and the
per-resource digests) and advances the catalog pointer — one transaction.
Releasing content byte-identical to a prior release is allowed (notes-only
re-release) but logged.

[`GetCatalogReleaseStatus`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/queries/GetCatalogReleaseStatus.kt)
is the shared primitive (latest version/fingerprint, working fingerprint,
`hasUnreleasedChanges`, suggested next bumps).

### What changed, resource by resource

[`GetCatalogResourceChanges`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/queries/GetCatalogResourceChanges.kt)
diffs the draft's per-resource digests against the `fingerprint` of each
`release_entries` row of the draft's base (the release it started from; the highest release below its
number when it records none), giving each resource one of NEW,
MODIFIED, RELEASED or REMOVED, and whether its current content is marked ready.
Both sides come from the same canonicaliser that computes the catalog
fingerprint, so a per-resource difference is exactly a fingerprint difference
localised to one resource and the drift check and the detail cannot disagree. It
is the authored mirror of the SUBSCRIBED upgrade diff, which reads
`installed_resource_fingerprints` the same way.

A release without those entries (one from 1.x; the 2.0 upgrade removes them) recorded
no baseline, and none can be reconstructed — the manifest snapshot holds the
manifest entries, not the payloads they were built from. While such a catalog
still matches its released fingerprint the working digests _are_ that release's
digests, so the answer stays exact; once it has drifted the query reports
UNKNOWN and says it has no baseline rather than calling everything new. The
next release records one.

## What an environment uses

Deploying (`DeployCatalogVersion`, contract `deployCatalogRelease`) names a catalog release by its
number or its `releaseId`, and adds it beside what the environment serves, in the UI as over
REST; `replace` (the UI's _Stop using the catalog's other versions here_) stops serving the others.
Each release deployment counts the generation requests accepted through the environment for it and
when the last was (`documents_requested`, `last_used_at`; `DeploymentUsage` tallies per node and
adds them about once a minute), so a version nobody asks for any more can be undeployed. Undeploying
resets the count. A deployed draft is not counted.

An environment serves catalog versions (contract ADR 0003, "Deploying"): any number of releases of a
catalog, and **at most one draft**, rendered **live**: what is changed in it is what the next
document gets, with no release in between. This is how work is tried before it is published.
Deploying another draft replaces it. There is no override: rolling back is undeploying the newer
release, and holding one back is not deploying it.

| A request through the environment asks for | It gets                                                |
| ------------------------------------------ | ------------------------------------------------------ |
| no version                                 | the deployed draft, else the highest release it serves |
| a range (`^1.4.0`)                         | the highest served release that fits; never a draft    |
| an exact release (`1.4.2`)                 | that release                                           |

A draft never matches a range or a number, as a SemVer pre-release does not, so a breaking draft based
on `1.2.0` is never served to `^1.2.0`. `ReleaseTargetResolver.select` makes the choice for
generation, batches, `PreviewDocument` and `AnalyzeTemplateData` alike; `GetEnvironmentLatest` reports
it per catalog.

- **Production.** An environment marked `production` serves only releases: deploying a draft there,
  and marking an environment production while it serves one, are refused
  (`EnvironmentDeploymentRefusedException`, REST 409 `environment-deployment-refused`). The migration
  marks environments keyed or named `production`, `prod`, `prd` or `productie`.
- **Documents from a draft** record the draft's id and its name at the time (`draft_id`, `draft_label`
  on requests and documents, no release version), validate their data against the draft's contract,
  and carry the preview watermark, so nobody takes one for a published document. They do not pin
  the draft's exact content: a document says which draft it came from and when, not a fingerprint.
- **A draft's lifecycle carries through.** Releasing a deployed draft keeps its `releaseId` on the
  release, so the deployment becomes one of that release, logged `RELEASED`; discarding it, or
  discarding the next version by making another draft the next version, undeploys it, logged.
- **History.** Every deploy, undeploy and release of a deployed draft is recorded in the environment's
  history (`DEPLOYED`, `UNDEPLOYED`, `RELEASED`, with the version's id, and the draft's name for a draft).
- **Dependencies of a deployed draft** are used as for a release: what its declarations pin is used
  implicitly (`ListImplicitDeployments`); `includeDependencies` deploys those pinned releases along,
  never a draft.
- **In the UI** the environment page marks the release that is _Latest here_, lists the work in
  progress it serves with _Undeploy_, and shows the production mark. A draft's version menu offers
  _Try it on an environment…_ (deploying it), listing production environments without offering them;
  the banner of a draft other than the next version names the environments serving it. _Try a
  document_ on the environment page makes a PDF of a deployed template as the environment serves it,
  with the template's first example data (`EnvironmentHandler.tryDocument` → `PreviewDocument`).
- **Over REST** `deployCatalogRelease` and `undeployCatalogRelease` (`…/deployments/{registry}/{namespace}/{catalogKey}/{release}`);
  deployments and history entries carry `releaseId`, `status` (`released` | `draft`), `version` and `releaseName`.
- **Over MCP** nothing deploys; `preview_document` and `analyze_template_data` with an
  `environmentId` render what the environment serves.

## Everyday work continuing from parallel work

Which draft is the next version is only a default: any draft is released as it is. There is no
separate operation to move the mark. Parallel work takes it over when it is published for everyday
work to continue from (`ReleaseCatalogVersion.continueEverydayWork`, `NextVersionHandOver`): the
release is made, and the draft continues on it as the next version, as the next version does on its
own release. The draft that was the next version is discarded with its changes (held resources and
other catalogs' drafts naming it refuse that, as discarding does) or kept as parallel work under
`keepPreviousAs`. Nothing is merged. A name in use, or a refused discard, refuses the release too.

In the UI the publish dialog of parallel work asks _After publishing_: everyday work carries on in the
next version (the default), or continues from this version, keeping or discarding the next version.
MCP `release_draft` takes `continueEverydayWork` and `keepPreviousAs`. REST has neither: the next
version is this suite's convention, not the contract's.

## Exporting a release

`ExportCatalogZip` exports a **release**: the one named by `version`, or without one the highest
release. It hands over that release as it was released, rebuilt by `ReleaseContentAssembler` from the
content it retained, carrying the version, timestamp and fingerprint it was cut with, whatever the
drafts have done since. An installed release exports the content it was installed with. The export
dialog offers the versions `ListRetainedReleases` reports, which is the same set the command will
accept, so the offer cannot fail at download. A catalog with no release has nothing to export.

`requirePublishable` does not run for a release. It describes a draft; the release passed it when it
was cut, it is immutable, and a rule that has tightened since must not retroactively make a
published release unexportable. The fingerprint _is_ recomputed and a mismatch refuses the export
— it should be impossible, and if it happens the retained content has been altered.

A version the catalog does not have refuses with `CatalogVersionNotFoundException` rather than
quietly substituting the working copy.

### With what it uses

`ExportCatalogZip(includeDependencies = true)` carries the releases the export pins inside the
archive, under `dependencies/` (contract ADR 0004), followed transitively; the bundled `system`
catalog is left out, as every installation has it. It is how a catalog reaches an installation that
receives catalogs as files. Refused while a pinned release is not here. The export dialog has
_Include the catalogs it uses_; a published version's page has _Export with what it uses_.

Importing one installs every carried release first, as subscribed when the tenant lacks its catalog;
one the tenant has at that release with the same fingerprint is left alone, and one with another
fingerprint, or a catalog of another type, refuses the whole import (`CatalogImportConflictException`,
REST `conflict`, 409). The catalog is then installed as asked.

## Importing installs a release

`ImportCatalogZip` installs the archive's release (contract ADR 0004). The import that creates a
catalog names its type (`catalogType`); after that the catalog's type decides. The same release with
the same fingerprint again changes nothing (`newlyInstalled = false`); the same number with other
content is refused. Into a **subscribed** catalog it replaces the installed release; a release lower
than the installed one is refused. Into an **authored** catalog it is added beside its releases with
the publisher's fingerprint and pins, and its drafts are untouched; a new authored catalog's next
version is opened on it. `intoDraft` (load an archive into a draft, `TEMPLATE_EDIT`) and `restore`
(snapshots) are internal.

A tenant snapshot exports each catalog's next version as a working copy, labelled with its latest
release or `0.0.0`; that is the only export of a draft.

## Publishing a release to Epistola Exchange

An AUTHORED catalog release can also create a durable Exchange publication
outbox entry in the same database transaction. The outbox retains the exact ZIP
for that immutable release; Exchange availability never determines whether the
local release succeeds. Imported releases suppress publication, and an existing
unchanged current release can be queued explicitly. Setting precedence,
namespace binding, status transitions, and retries are specified in
[`catalog-exchange-publication.md`](catalog-exchange-publication.md).

## Importing a ZIP

A ZIP import always targets a catalog **type**. A slug that already exists with
a _different_ type is rejected (it would flip ownership semantics). What the
import then means depends entirely on that type:

### AUTHORED — the archive's release, added beside yours

An import into an authored catalog adds the archive's release as it was published: its number,
fingerprint and pins, never a merge into a draft (see [Importing installs a release](#importing-installs-a-release)).
Its drafts are untouched; they are behind the new release if it is above their base. The import that
creates the catalog opens its next version on the release. There is no MCP import tool, so import is
not an MCP surface.

### SUBSCRIBED — releases installed side by side

Nothing is upgraded (contract ADR 0003). A subscribed catalog holds the releases it installed side by
side, each an immutable release with its retained content, and installing another adds it, whether
it is numbered above them or below. Its source, a manifest URL (`ManifestUrlReleaseSource`) or
Epistola Exchange (`ExchangeCatalogReleaseSource`), offers releases:

- **`ListAvailableCatalogReleases`** (contract `listAvailableCatalogReleases`, MCP
  `list_available_catalog_releases`, the UI's _Available releases_) lists every release the source
  offers that the tenant lacks, lowest first: a fix `1.4.1` is listed while `2.0.0` is installed.
  Each is compared to the closest installed release below it (`comparedTo`), with the resources it
  added, modified or removed against that one, and the releases it pins, each with whether it is
  here. A manifest URL offers the one release it describes now; Exchange lists every installable one.
- **`InstallCatalogRelease`** (contract `installCatalogRelease`, the UI's _Install_) installs one, as
  importing its archive does, after installing the releases it pins that the tenant lacks from the
  source serving their registry (Exchange); one none can supply refuses it
  (`release-dependencies-missing`). The same release again changes nothing; the same number with
  other content is refused (409 `conflict`). A release above the installed one also becomes what the
  catalog mirrors in its content tables; one below is recorded beside the others from its archive.
- A source that cannot be reached answers 503 `catalog-source-unavailable` (with `Retry-After` when
  it gave one), one that answers badly 502 (`CatalogSourceUnavailableException`).

Importing a ZIP into a subscribed catalog is the same install with the archive in hand.

## Bundled catalogs

`demo` and `system` ship a real SemVer and a committed `release.fingerprint` in
their `catalog.json`.
[`BundledCatalogFingerprintTest`](../modules/epistola-core/src/test/kotlin/app/epistola/suite/catalog/BundledCatalogFingerprintTest.kt)
recomputes the fingerprint from classpath content and asserts it equals the
committed value — the drift gate (analogous to the `pg_dump --schema-only`
migration check). Editing a bundled resource without regenerating the committed
fingerprint fails the build. `DemoLoader` and `InstallSystemCatalog` decide
install/upgrade/no-op by **fingerprint** (`manifest.release.fingerprint` vs
`catalogs.installed_fingerprint`), logging the SemVer transition for humans.

To regenerate after an intentional change: run the test, paste the reported
"actual" fingerprint into the catalog's `catalog.json`, and bump
`release.version`.

## Surfaces

| Surface  | Exposure                                                                                                                                                                                |
| -------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Web UI   | Version column (`v<version>` / `unreleased`); drafts (start, discard, make the next version, publish any with `acknowledgedBehind`)                                                     |
| REST     | Catalog releases: list (drafts with `?status=draft`), create a draft, rename, discard, preview a draft's release number, release a draft (`…/releases/{next, release id or number}/…`)  |
| MCP      | `list_catalog_versions`, `start_draft`, `rename_draft`, `discard_draft`, `preview_release_number`, `release_draft` (with `continueEverydayWork`); `catalogVersion` on the content tools |
| Exchange | `ReleaseInfo.fingerprint` in the manifest; `schemaVersion` 4                                                                                                                            |

## Roadmap

- **Phase 2 — upgrade diff/preview.** _Backend landed._ A SUBSCRIBED consumer
  has **no `catalog_releases` snapshot** (that table is AUTHORED/publisher
  state), so the diff is **source-vs-source**, not snapshot-vs-installed: the
  per-resource source-side digests are captured into
  `catalogs.installed_resource_fingerprints` (JSONB) at `RegisterCatalog` /
  `UpgradeCatalog`, exactly alongside `installed_fingerprint` and with the same
  provenance (computed from the source manifest, never publisher-authored).
  `PreviewCatalogUpgrade` re-fetches the manifest, recomputes the same
  per-resource digests, and classifies every `(type, slug)` as
  ADDED / REMOVED / CHANGED / UNCHANGED — so a CHANGED verdict means the
  _publisher_ changed that resource, never install round-trip noise. The
  per-resource digest reuses the **exact** whole-catalog fingerprint pipeline
  (`CatalogCanonicalizer.perResourceFingerprints*`); cross-catalog conflicts on
  the removal set are computed by the shared `CatalogUpgradeAnalyzer` that
  `UpgradeCatalog` also throws on, so a blocking conflict surfaces in the
  preview before Apply. **UI:** an **explicit, user-driven** check — a refresh
  **icon** in the row's actions cell (never auto-polls) renders the result in
  the **Version column**: green `vX` (up to date), greyed `vX` + clickable
  `→ vY` (update available — the new version named; opens the Review dialog),
  `vX · ZIP` (ZIP-managed mirror — no source URL, upgrade by re-importing),
  or `vX · check failed` (source unreachable). The "Review catalog upgrade"
  dialog shows the version delta, change buckets, conflicts with a
  server-rendered disabled Apply, and the resources new in this release listed
  for information — an upgrade reconciles the whole manifest, so they arrive
  with it rather than being opted into. **Read parity:** REST
  `GET /tenants/{tenantId}/catalogs/{catalogId}/upgrade-preview`
  (`CatalogUpgradeDiff`) and MCP `preview_catalog_upgrade`; the upgrade
  _action_ stays UI-only. Still open: ZIP-import dry-run.
- **Phase 3 — dependency SemVer ranges.** Done in 2.0 as declared dependencies; see
  [Declared dependencies](#declared-dependencies).
