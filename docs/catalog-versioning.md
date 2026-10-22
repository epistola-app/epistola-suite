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
`installed_release_version` (then a column of subscribed catalogs only). An author could not say
"this is now a new version" with any meaning, and re-export produced a
different "version" even when nothing changed (or the same one when it did).

## Model

A catalog is **one row per `(tenant_key, slug)`**, with one live resource set per open draft (see
[drafts](#catalog-versions-and-drafts)), and its **releases**, each kept with the content it was cut
with. There are no catalog types (ADR 0032): every catalog has drafts and releases, and what differs
is only whether it can be changed here and where its releases come from (below). `catalogs.type` is
still in the table, defaulted and unused, and goes in a later major release.

- **Releases** — `catalog_releases` holds every release the catalog has, whether it was cut here or
  received: SemVer, content fingerprint, notes, a manifest snapshot, and one `release_entries` row
  per resource naming the revision that holds its content and the digest it fingerprinted to.
- **Released here** — cutting a release advances `catalogs.released_version` /
  `released_fingerprint` / `released_at`.
- **Received** — receiving a release (an import, an Exchange or registry install, an upgrade from a
  source) records it and, when the next release moves to it, sets `installed_release_version` +
  `installed_fingerprint`; `installed_resource_fingerprints` (JSONB) records the per-resource
  source-side digests of that release (the complete form, naming its function), the upgrade-diff
  baseline for a catalog with a source.

A catalog can carry both pointers: one received from elsewhere and then released here has both.
Its version is its **highest release**, by number (`Catalog.highestReleaseVersion`). A
`1.4.0` exported and received elsewhere flows its version and fingerprint through the manifest into
the receiver's release and `installed_*`, so values stay coherent end to end.

## Who may change a catalog, and what receiving a release does

ADR 0032 replaced the catalog types (`AUTHORED`, `SUBSCRIBED`) with the rules below.

- **Every catalog has a next release**, the draft its browsable content lives in. The database opens
  it when the catalog row is inserted, on the release it was inserted with, and moves it when an
  install changes `installed_release_version` (`V20261021081527`). A received catalog's next release
  starts on the release it installed (`base_version`); there is no installed copy any more
  (`V20261021063432`).
- **A catalog can be changed** when the installation allows editing
  (`epistola.catalog.editing.enabled`, default `true`) and the suite does not supply it (a
  `classpath:` source: the system and demo catalogs). `GetCatalogEditability` says which, and
  `requireCatalogEditable` refuses with the reason (`CatalogReadOnlyException.reason`). With editing
  off the installation is **run-only**: nothing in a catalog is started, changed, merged or
  released, but it still manages its releases. It receives (installs), deploys, rolls back and
  deletes them: those are operating the installation, not editing a catalog, so they check their
  own permission (`CATALOG_MANAGE`) and never editability (`RunOnlyInstallationTest`). Where
  releases come from does not matter: a received catalog can be changed and released like one made
  here.
- **Receiving a newer release** (an import, an Exchange install, an upgrade from a source) records it
  and, when the next release has no changes of its own, moves the next release to it (a
  fast-forward). With changes of its own (`NextVersionChanges`), the release is recorded beside the
  others and the next release is behind it, to be merged; an upgrade from a source is refused
  (`CatalogHasLocalChangesException`), since it would discard them. Changes are its resources and its
  catalog settings (`catalog/defaultTheme`, `catalog/presets` and one `catalogDependency/<catalogKey>`
  per catalog it uses, as a merge compares them), so a next release whose only change is its default theme is not moved along either.
  They are measured against what the next release held when the release was put in place
  (`catalog_versions.base_fingerprints`, set by `RecordInstalledRelease`), else against its base
  release's content, by the [complete fingerprint](#what-changed-resource-by-resource).
- **The web UI and MCP decide by this, not by type.** A page offers drafts, edit controls and
  Publish for a catalog that can be changed here, received or not, and shows a read-only catalog's
  reason ("Supplied by Epistola", "This installation only runs releases", the reason's `label`) with
  the release its content is on. What depends on a source stays keyed on having one
  (`Catalog.hasSource`): checking for and installing newer releases. Lists ask once per page
  (`ListCatalogEditability`), and a catalog's version is its highest release, released here or
  received (`Catalog.highestReleaseVersion`). No import asks for a type: every import receives the
  release (see [Importing a ZIP](#importing-a-zip)).
- **What else follows editability**: moving resources between catalogs (both must be changeable),
  the house theme (applied to the next release of every catalog that can be changed), declaring a
  catalog a write starts using (its next release when it can be changed, else its highest release),
  the catalog list's _pending changes_ hint, getting started, and the ordering of usage lists.
- **A tenant snapshot** still writes `SUBSCRIBED` for a catalog with a source and `AUTHORED` for any
  other in `snapshot.json`, because the hub shares the format; a restore ignores it and fills every
  catalog's next release.

## Catalog versions and drafts

A catalog consists of **versions** (contract ADR 0002): released ones, kept in
`catalog_releases` with their retained content, and the versions whose content is in the content
tables, identified by `catalog_versions`: the catalog's open **drafts**. A draft has **no number
until it is released**; only releases are numbered.

- **Every draft is the same; one is the next release.** Any draft can be released (contract ADR
  0002). Every catalog marks one draft the **next release**
  (`catalog_versions.is_next`, one per catalog): the draft everyday work goes into and the one a
  caller that names no version means. The mark is a suite default, not a kind of draft. Every other
  draft is named (`catalog_versions.name`, unique per catalog ignoring case): a redesign, a fix to an
  older line, work tried by deploying it to an environment. A version is identified by its
  **version id** (`version_key`, eight characters such as `k7m2x9qa`, from `epistola_version_key()`),
  which a release keeps from the draft it was released from; `next` names the next release.
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
  the **next release** starts again as a copy of the latest release (`NextVersionOpener`), so everyday
  work always has somewhere to go. It is refused while a draft of another catalog names it as a dependency
  (`CatalogDraftInUseException`), and (`CatalogReleaseHeldException`, REST
  `CATALOG_VERSION_HELD`, 409, `heldBy`) while the draft alone holds a resource something outside it
  still references: the tenant's house theme, a code list an attribute of another version or
  catalog binds, or a theme a template of another version or catalog uses. Discarding would delete the
  resource's identity, and those references would fail as a foreign-key violation or, for a template's
  theme, be cleared silently. The refusal names each holder, e.g. `the tenant's default theme (theme
  letters/seasonal)`.
- **Theme settings** (contract ADR 0007). A version carries its default theme and the presets it
  declares (`catalog_versions.default_theme_resource_id`, `presets`). A release freezes both in its
  manifest's `catalog` and renders with them; new parallel work copies them from the version it starts
  from, and an import takes them from the archive. A default theme of another catalog
  (`SetCatalogDefaultTheme`) is looked for where the version reads that catalog from: the release its
  declaration pins, or the draft it names; only an undeclared catalog is read from its current version
  (and then declared).
- **Behind.** A draft is _behind_ every release of the catalog numbered below the number it would be
  released as that its line does not contain (`ReleaseLineage.behind`). Its line is its base and the
  releases it merged, with everything those contain in turn: each release's base and the releases
  merged into its draft before it was released (its merge parents, below). Numbers and times say
  nothing about it: a fix released long before the next release last moved on is still behind, and
  one merged into a release before it is never behind again. A release on the line that records no
  lineage (received from an archive that named no base) cannot say what it contains, so it is taken to
  contain every release numbered below it. It lists each with what it changed against its own base.
  Releasing a draft that is behind is refused (`CATALOG_VERSION_BEHIND`, 409) until
  `acknowledgedBehind` names every one. Behind is computed only when asked for
  (`ListCatalogReleases(withBehind)`, `GetCatalogDraft(withBehind)`), since it reads every release's
  content.
- **Merging** (alpha, ADR 0032). A draft takes in a release it is behind with
  `MergeReleaseIntoDraft`; `PreviewDraftMerge` says first what that does. Per resource, and per
  catalog setting (the default theme and the presets, entries `catalog/defaultTheme` and
  `catalog/presets`, and each catalog it declares it uses, one entry `catalogDependency/<catalogKey>`
  valued by its declaration: the release it pins with its fingerprint, or the draft it names), against
  the merge base, the
  closest release both descend from: what only the release changed is taken (written as the release
  has it, or removed), what only the draft changed stays, and an entry both changed differently is a
  conflict the person resolves to one side, or to merged content (below). Taken resources are marked
  ready, since they are released content. A release that removes something the draft still uses is refused
  (`MergeRefusedException`), as is one the draft already contains. So is a merge whose result holds a
  reference that does not resolve (`MergeReferenceCheck`): within the catalog to a resource the result
  has, into another catalog only through a dependency the result declares, to the release it pins
  (not that catalog's next release) or the draft it names; the default theme likewise. It says what is
  missing and what to choose, e.g. "the default theme 'brand' of 'shared' needs 'shared' as a
  dependency: take v1.0.1's dependency on 'shared' too, or keep your default theme". A dependency
  nothing refers to any more is kept, as editing keeps one. The preview lists with each entry the
  entries it goes with (`MergeResource.needs`: the theme or stencil a template uses, the dependency a
  reference into another catalog resolves through), which the dialog shows as "Goes with …" and MCP
  and REST as `needs`. The preview carries a `token`; a
  merge given it is refused once the draft or the release changed since
  (`MergePreviewOutdatedException`, REST 409 `merge-preview-outdated`: preview again). The draft records the merge
  (`catalog_draft_merges`), which `behind` then leaves out; its base does not move. Merging inside one
  resource is later work; see [Merge internals](#merge-internals).
- **Merged content** (alpha, contract ADR 0005). A conflict can be resolved to content combined from
  its sides instead of one side, by a person, an assistant or a tool. `GetDraftMergeSides` reads one
  entry the preview lists on its three sides, in the portable catalog format: a resource as the
  `resource` object of its resource detail (the form an archive carries), the default theme as
  `{"defaultTheme": …}`, the presets as `{"presets": […]}`, a dependency as its manifest
  declaration (with `releaseId`, the draft's `versionId`, when it names a draft), each null where a
  side lacks it, with the merge base and the preview's `token`; without the merge base it is
  refused (`MergeBaseMissingException`), and for an entry the merge does not list it is not found
  (`MergeEntryNotFoundException`). `MergeReleaseIntoDraft.mergedContent` (`type/key` to that form)
  resolves a conflict beside `resolutions`, one of the two per conflict (both is a
  `ValidationException`, REST 400). The content is read as an import reads it
  (`MergedContentReader`): a resource is bound through the portable schema migrator, must name the
  entry's `type` and `key`, and is checked by the portable resource validator, then written by the
  import's own installer, so the suite's rules apply too; a setting's value is parsed. Invalid content
  is a `ValidationException` whose field names the entry and the path within it
  (`mergedContent.template/invoice/variants[1]/key`, REST 400 `validation-error`); content for an
  entry that is not a conflict is `MergeNotApplicableException`. A reference merged content holds
  that the result lacks is refused by the reference check like any merge's (`merge-refused`). Merged
  content is written as given and **not marked ready**, unlike taken content: the draft's page and
  the publish dialog badge it "Merged, review before publishing", and the dialog names it ("These
  were merged, review them: …"), so publishing it, which marks it ready, is deliberate. The badge
  comes from the draft's merges (`MERGED` in `catalog_draft_merges.resolutions`, read by
  `ReadyMarks.mergedAwaitingReview`) and lasts until every part of the resource is marked ready at or
  after the latest merge that merged it; an edit alone does not end it, and a later merge that takes
  the release's side marks it ready. MCP `get_catalog_changes` returns it as `mergedForReview`; REST
  `getCatalogChanges` has no such field in the contract yet. The merge returns it as `merged`.
  Surfaces: MCP `get_merge_sides` and `merge_release`'s `mergedContent`; REST `getCatalogDraftMergeSides`
  (`GET …/releases/{draft}/merge-sides?version=&entry=`) and `mergeIntoCatalogDraft`'s
  `mergedContent`; the web UI's merge dialog shows a conflict's three sides read-only ("Compare") and
  still resolves it to one side.
- **The merge base is found by lineage, never guessed** (contract ADR 0005). It is the closest release
  on the merged release's line (itself, its base, that one's base, …) that the draft contains, each
  link a base a release records. A release that records none ends its line; the release numbered
  below it is never taken for one.
  - Found and installed, and the installed release of that number is the one the line names by
    fingerprint: merged against its content.
  - Found by lineage but not installed, or another release under that number: the preview names it
    (`DraftMergePreview.missingBase`, REST `missingBase`) and classifies nothing, and the merge is
    refused (`MergeBaseMissingException`, REST 409 `merge-base-missing` with `version` and
    `fingerprint`). The web UI says "To merge v1.0.1, this installation needs v1.0.0, the release both
    come from. Get it first (from a registry, the Exchange or a file).", and offers no merge until it
    is; MCP says the same (`missingBase`, `notice`). Installing it beside the others (an import,
    `installCatalogRelease`) makes the merge possible.
  - No common release known (a release from before `release.base`, or from a producer that did not
    know it): no base, and every entry the two have differently is a choice; the dialog and MCP say so.
- **A release records its base** (`catalog_releases.base_version`, `V20261018200200`, and
  `base_fingerprint`, `V20261021104938`): the release its draft started from, which `getCatalogRelease`
  reports as a released release's `base` and which `behind` measures its changes against. A received
  release keeps the base its archive names (`release.base`). It is null for a catalog's first release,
  a release from an archive that names none, and every release cut before the column; for the list of
  what a behind release changed, those are measured against the release below them, which is only a
  display.
- **A release records its merge parents** (`catalog_release_merges`, `V20261022052418`): the releases
  merged into its draft (`catalog_draft_merges`) when it is released, each with its fingerprint. The
  manifest says them too, in `release.metadata` under `app.epistola.suite` as
  `merged: [{"version": "1.0.1", "fingerprint": "v6:…"}]` (a bare version is read as well), so an
  archive, an installation receiving it and a tenant snapshot keep them: receiving records them, and an
  export of a release recorded without them in its snapshot names them from the table. Lineage is base
  plus merge parents (`ReleaseLineage`); the merge base and `behind` both follow it.
- **Releasing** (`ReleaseCatalogDraft`, contract `releaseCatalogDraft`) releases any draft,
  numbered as asked or, without a number, as its changes need against its base
  (`AssessDraftNumber`, contract `previewCatalogDraftRelease`: `base`, `changeKind`, `needed`,
  `suggested`): breaking changes the next major, additions the next minor, anything else the next
  patch; a draft with no base starts at `1.0.0`. The suggestion is the next free number of that kind,
  so a fix to 1.2.x on a catalog that has 1.2.1 and 2.0.0 is 1.2.2. A number must be free, above the
  base and never lower than the changes need (`CatalogReleaseVersionException`); the author may raise
  it, to mark a major on purpose. The release takes the draft's version id. Releasing the next release
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
  a dependency added (an installation brings it along). Presets (contract ADR 0007) follow the
  resource rule, since content of a catalog that uses this one may name them: one removed is
  breaking, one added additive, one renamed or described differently a fix. Everything else is a
  fix, including a dependency removed (it changes nothing others rely on), another default theme
  (it decides how this catalog's templates that name no theme look, as a theme's own content does)
  and another license. The same rules
  decide whether a newer release of a catalog this one uses fits what it uses (`GetCatalogBorrowing`).
- **Relocation** moves the next release's rows, so it is refused (`several-drafts`) while the source
  or target catalog has another draft.

### Which version a statement means

Every statement on content names its version through the helpers in
[`CatalogSql.kt`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogSql.kt):
`versionAt(t, c)` and `inCurrentVersion(alias)`, both over the view `catalog_current_versions`;
`ContentVersionSqlTest` fails a statement that names none. The view answers, per catalog, the version
the caller means:

1. the version the transaction **targets**, when it is a version of that catalog;
2. else the catalog's **next release**. Another draft is reached only by targeting it.

A command or query that addresses one draft implements
[`CatalogReleaseScoped`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/releases/CatalogReleaseScoped.kt)
and carries a `CatalogReleaseRef` (tenant, catalog, `DraftRef`: `Next` or `Id`). `SpringMediator`
resolves it before the handler runs (an unknown draft is `CatalogReleaseNotFoundException`), runs a
query that names one in a read-only transaction, and `CatalogReleaseTargeting` sets the PostgreSQL
setting `epistola.content_version` to the draft's `version_id`, transaction-locally. Handlers written
for "the" version of a catalog then work on that draft unchanged, and every message they dispatch
inherits it; the caller's target is restored when the message ends. A message that names no version
works on the next release. A self-managed command (`RefreshCodeList`) applies the target in each
transaction it opens.

The REST API's `…/releases/{release}/…` paths take `next`, a draft's release id or a release number
(`ResolveCatalogRelease`), pass the draft they name to every message they dispatch
(`ResolvedCatalogRelease.ref`), and read a released version from its retained content.

### Choosing a draft in the UI

The web UI names a draft other than the next release in the catalog's own path segment:
`letters@<version id>` is that draft of `letters`, plain `letters` its next release (`CatalogSegment` in `epistola-web`; `@` cannot occur in
a catalog key). So `/tenants/acme/catalogs/letters@k7m2x9qa/templates/invoice` is the invoice in that work, and the
same works for every per-catalog and per-resource route (catalog page, themes, stencils, code lists,
fonts, images, the template editor and its preview). List pages take it as their `catalog` filter
(`/catalogs/letters@k7m2x9qa/attributes`), and their create dialogs then create there.

- **Why the URL, not the session.** A link to a draft is shareable and survives reloads, two tabs
  can work in two drafts, and every link, HTMX request and editor call built from the segment stays
  on the draft without any of them knowing about drafts. The editor's own requests read the segment
  from the editor config (`catalogSegment`) and use it for its own catalog's URLs; requests across
  catalogs (stencil search, image content) name the draft as `in=letters@…`, so that catalog is read
  from the draft and every other from its next release.
- **Every message names it.** Handlers pass the segment's draft as `catalogVersion` to every command
  and query they dispatch, including the UI-only ones (`GetEditorContext`, `ListTemplateSummaries`,
  `GetThemeUsagePage`, `ResolveTemplateTheme`, `PreviewVariant`, `BrowseCatalog`, …).
- **The context bar** (read model `CatalogDraftBar`, [`workspace-shell.md`](workspace-shell.md)) says
  which draft a page works in: "Next release", or another draft by its name with a strip saying it is
  kept apart (the UI calls such a draft _parallel work_). Its version menu lists the next release and each piece of parallel work, and offers
  _Start parallel work…_ (`catalogs/drafts.html`: a name, and a published release or the next release
  to start from).
- **Publishing** is offered for every draft, numbered from its changes against its base; the release
  dialog lists the releases it is behind, with their changes, and asks to confirm each
  (`acknowledgedBehind`). _Discard_ on the next release throws its changes away; on another draft it
  removes it.
- A route naming a draft the catalog does not have is a 404; a number names no draft.

Over MCP the content tools take an optional `catalogVersion` argument (`next` or a version id), and
the version tools list versions, start, rename and discard drafts, preview a draft's release number,
release a draft and make one the next release (see [`mcp.md`](mcp.md)).

## Versioning scheme: SemVer + content fingerprint

- **SemVer** (`MAJOR.MINOR.PATCH`) is author-controlled, recorded per release.
  Implemented by the dependency-free
  [`SemVer`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/SemVer.kt)
  value type (parse / compare / bump). Releases must strictly increase.
- **Patch releases** (contract ADR 0003, amendment of 2026-10-22; ADR 0034): someone who does not hold
  a catalog's namespace numbers a release `MAJOR.MINOR.PATCH-<namespace>.<N>` (`1.3.1-gemeente-x.1`),
  labelled with a namespace they hold. A release number is up to 128 characters
  (`V20261022110352` widened every column that holds one). This installation **receives** patch
  releases (an import, the Exchange, a registry) and orders them as SemVer does wherever releases
  are ordered or compared: above the release they fix and below the holder's next (`1.3.0` <
  `1.3.1-bureau.9` < `1.3.1-gemeente-x.1` < `1.3.1-gemeente-x.2` < `1.3.1`), in the catalog's highest
  release, the latest, the release lists, behind and the merge base, and what a version range picks:
  a patch release satisfies a range as a release does, by precedence (`^1.3.0` matches it, `1.3.1`
  and `~1.3.1` do not), not as an npm pre-release, which a range naming none leaves out
  (`CatalogReleaseSelector`). SQL orders by the generated `version_major`, `version_minor`,
  `version_patch`, `version_label` (ASCII) and `version_label_number` (`releaseNumberOrder`).
  **Issuing** one is ADR 0034's phase 5, with the rule of who may issue which: releasing here, in
  the UI, over REST and MCP, still takes `MAJOR.MINOR.PATCH` (`CatalogReleaseVersionException`), and
  the REST API's `ReleaseVersion` cannot name a patch release yet (contract follow-up).
- **Content fingerprint** is a deterministic lowercase-hex SHA-256 of the
  catalog's canonical content, computed by
  [`CatalogCanonicalizer`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogCanonicalizer.kt)
  (wired by `CatalogFingerprintService`). It identifies _content_ independently
  of the version label — "is this actually new?" is a fingerprint comparison,
  not a string compare.

### A release is identified by its content hash

A release's identity is its **content hash** (contract ADR 0003, amendment of 2026-10-22; ADR 0034,
phase 2): the **V6** fingerprint (`v6:<hex>`), which covers the release's content only — its
resources, the resource index, its default theme, presets and license, its dependencies by alias and
the pins of each, and `compatibility` — and not the catalog's name, the release block (number, id,
metadata, base, release time), the catalog's presentation (name, description, keywords, presentation,
attributes, publisher, includes) or the wire version. Two releases with the same content hash are the
same release, whatever number, name or route they came by; the number stays what people,
deployments and version ranges use.

- **New releases** are fingerprinted with V6, so their fingerprint is their content hash. Every
  archive this installation writes (an export, the Exchange, a registry, a tenant snapshot) names
  the release by its content hash, as wire v8 requires (`ReleaseIdentity.travelling`), whatever
  fingerprint the release was cut with.
- **Existing releases** keep the fingerprint they were cut or received with (V5 or older: immutable
  and verifiable with the algorithm it names), and record their content hash beside it:
  `catalog_releases.content_hash` (`V20261022104231`), set from the fingerprint when that is V6, else
  computed from what the release retained by `ReleaseContentHashBackfill`, a single-owner cluster
  task at startup, or on first use (`ReleaseIdentity.contentHash`). The hash needs the contract
  library's canonical form, which SQL cannot reproduce, and reading every retained revision back,
  which a Kotlin migration would do inside the upgrade transaction on the startup path, so it is not a
  migration.
- **"Is this the same release?"** is `ReleaseIdentity.isRelease`: the release's recorded fingerprint
  or content hash, or, for a fingerprint of another algorithm (a pin or base recorded as V5), that
  algorithm over its retained content. It decides receiving a release the tenant has (the same
  number with the same content hash is a no-op, however either side was fingerprinted), whether a
  dependency pin holds (importing, deploying, releasing), rendering a pinned release, the merge base,
  whether a source or registry offers a release the tenant has (`CheckCatalogUpgrade`,
  `PreviewCatalogUpgrade`, the bundled catalogs, `PublishReleaseToRegistry`), and the lineage below.
- **Lineage is by hash.** A release cut here names its base and merge parents by their content hash
  (`base_fingerprint`, `catalog_release_merges.merged_fingerprint`), the number kept for people, and
  `ReleaseLineage` resolves a parent named by a fingerprint to the release here recorded with that
  fingerprint or content hash, whatever its number: a line survives a release received under
  another number.
- **Pins** copy the pinned release's content hash.
- **People see it on the release page.** "Content fingerprint" shows the content hash, short with the
  whole of it in the title and a copy button, or "Being computed" while a release recorded before
  content hashes waits for the backfill (the page computes nothing). Such a release also shows its
  "Original fingerprint", saying it "was recorded before content fingerprints and has been given
  one". "The same release as" links the other releases of the tenant with the same content hash, in
  any catalog and under any number (a release re-released with only new notes, the same content in
  another catalog): `ListReleasesWithSameContent`, which reads only what is recorded, so a release
  whose hash is not computed yet is not listed. MCP `list_catalog_releases` returns each release's
  `contentHash`; the REST API's release DTOs show the recorded fingerprint only (no field in the
  contract yet).
- The storage is unchanged (ADR 0033 is separate): `catalog_releases` stays the record of a release
  under its catalog and number, which in ADR 0034's terms is the binding of that number to the hash.

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

A catalog that can be changed here edits these fields from the catalog browse page; one that
cannot (Epistola supplies it, or the installation is run-only) shows them read-only. URL registration, URL upgrade, ZIP
import, export, release snapshots, and tenant snapshots preserve the same typed
value. A metadata-only publisher edit appears in the upgrade preview and can be
applied without fabricating a resource change.

Presentation media is deliberately **installed-only**. Epistola never proxies
or auto-downloads an uninstalled source asset just to render catalog chrome.
The browse page shows an unavailable placeholder; local exports and snapshots
omit missing icon references and filter missing gallery entries while preserving
the order of installed entries. The full source metadata remains stored so the
presentation appears as assets are installed later. The metadata is catalog-wide and the
images belong to a version, so every catalog's content names only the images the version it is
built from holds.

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
`catalogDependencies`, contract ADR 0003): under an **alias**, by registry, namespace and key,
**pinned to one exact release** of it (`version`), not a range. Content names the catalog by the
alias and a resource key (a reference's `catalogKey`, a template's `themeCatalogKey`, the default
theme's `catalogKey`, a variant attribute's qualifier such as `system.locale`) and never by registry
or namespace, so renaming or mirroring a dependency changes the declaration, not the content (ADR
0034). This installation tells catalogs apart by key within a tenant until ADR 0034's phase 4, so it
declares every dependency under its key, the contract's default (`catalog_dependencies.alias`,
`catalog_release_dependencies.alias`, `V20261022101420`), and refuses an archive that declares one
under another alias (`CATALOG_DEPENDENCY_ALIAS_UNSUPPORTED`): storing it would rewrite its references
and so change its content hash. The draft page's Dependencies table shows each declaration's alias
("Named in content as") next to its namespace and key, and so does the catalog's Dependencies page
("Depends on"), where people add one; a catalog used there but not declared shows the key its
references name. A reference resolves through the declaration of its alias to that
catalog, by its full name, and to the release the declaration pins. A draft may instead name a **draft** of that catalog
(`releaseId`; column `dependency_version_id`), to build on work not released yet; it renders with that
draft. `UpdateCatalogDependencies` (`TEMPLATE_EDIT`) replaces the declarations of a catalog;
each must name a catalog the tenant has, not itself, not twice, and a release it has with a
fingerprint that names its algorithm (a release cut before 2.0 has none) or a draft it has; the pin
copies that release's content hash. The pin stays when the dependency
releases again; `UpgradeCatalogDependencies` moves every pin to the highest release, the way a
library upgrade does, and leaves a declaration naming a draft as it is (the UI's _Use the latest
versions_). Renaming the dependency's namespace carries the declarations that name it.

**Declared as it is used.** A write that starts using another catalog declares it
(`UsedCatalogDeclarer`), on every surface: a variant's stencils, images and fonts, a variant attribute of another catalog (`shared.tone`), a template's theme,
a theme's fonts, a stencil's content, an attribute's code list. A catalog that can be changed here is
declared by naming its **next release**, which is what the draft then renders it from; one that cannot
by pinning its highest release. A declaration already there is left as the author set it.

**Catalogs never use each other.** A declaration naming a catalog that already uses this one,
directly or through others, is refused, and so is the write that would add it, in the same
transaction (`CatalogDependencyStore.replace`, `ValidationException` with
`CATALOG_DEPENDENCY_INVALID`, naming the way round: `'letters' uses 'shared' uses 'base'`). It is
judged by what every other version declares: each draft of each catalog, parallel work included. 2.0 could not release such catalogs, since each would
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
release (no resource or catalog setting changed against its base, no draft named, the same pins as
its base) pins that
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
nearest first. A pinned release the tenant no longer has, or has as another release (another content hash), fails the
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

**Moving** a resource into another catalog declares what the move creates: every catalog that can be
changed here whose content now references a catalog it did not declare gets a declaration pinned to that
catalog's highest release, or naming its next release when it has none (`MissingDependencyDeclarer`).

**Installing** keeps the archive's pins: a received release's declarations and pins are the
publisher's. Every pinned
release must be present, carried in the archive or installed already
(`CatalogImportDependenciesMissingException`, REST `release-dependencies-missing`).

**Upgrading from 1.x** declares what 1.x left undeclared, and cuts each catalog's first release:
1.x had no declarations, and no 1.x release kept its content. The upgrade queues every authored
catalog that exists then (`catalog_upgrade_queue`), and a background task (`CatalogUpgradeRunner`)
gives each a declaration for every catalog it references, pinned to that catalog's highest
release (as a move does), and pins every declaration still unpinned (`pinUnpinned`), then releases it from what was last published before 2.0, numbered the
patch after its highest 1.x release, or 1.0.0. The upgrade migration records that number in the queue
(`continues_after`) and removes the 1.x releases, which kept no content, so each catalog starts 2.0
with that one release and its next release on top. A template whose default variant was never published, and a stencil never published, are left
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
- A catalog that cannot be changed here is read-only: nothing in it can be marked.
- Receiving a release marks everything it put in the next release ready — it is released content.

`ReleaseCatalogDraft` refuses with `CATALOG_RESOURCES_NOT_READY` while any resource that is new or
modified since the last release — of any type — is not ready. `GetCatalogResourceChanges` (below)
reports each resource's state and whether it is `ready`.

**Catalog settings carry no ready mark.** A dependency added, removed or locked to another release,
another default theme, other presets or another license is a change of the release (below), and
never waits on a mark: choosing one is a single deliberate act with nothing half-done to review, and
a mark records a resource's content digest, which a setting does not have. So a draft whose only
changes are catalog settings is publishable as it is, and "Mark all ready" has nothing to mark.

The 2.0 upgrade (`V20261018200600__core_working_copy.sql`) gives each variant its
draft model, else its latest published, else its latest archived; each template its draft contract,
else its latest published. What was publishable before is marked ready: a variant with no unfinished
draft, a stencil without a draft, and every other resource. `template_versions` and
`contract_versions` stay as history nobody reads.

## Cutting a release

The page of a catalog that can be changed here has a **Next release** section (`catalogs/next-release.html`): every
resource that changed since the draft's base with its state and whether it is ready, then every
catalog setting that changed (below), **Mark all
ready**, and the declared **Dependencies** with their locks (edited in a dialog: the catalogs it uses,
each tied to its latest published version, which a newer one replaces once it fits what is used;
**Resolve to latest** moves the locks). The next release has no number until it is published; the
release dialog picks it from the kind of change and may raise it under Advanced. **Release** is offered only while nothing blocks it; otherwise the
section says which resources are not ready or which dependencies match no release, and where to fix
it. The page and the release dialog gate on the same read model, `CatalogReleaseReadiness`. The
dialog (also reachable from the catalog list) pre-fills the draft's number, offers Patch / Minor /
Major quick-picks and release notes, and names what this release will add, update and drop, catalog
settings included. It dispatches
[`ReleaseCatalogDraft`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/commands/ReleaseCatalogDraft.kt):
refuses for a catalog that cannot be changed here and while a changed resource is not [ready](#ready-marks),
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
diffs the draft's resources against those of the draft's base (the release it started from; the
latest release when it records none), giving each resource one of NEW, MODIFIED, RELEASED or REMOVED,
and whether its current content is marked ready.

Resources are compared by their **complete fingerprint**,
`CatalogFingerprintService.resourceFingerprints` (contract `completePerResourceFingerprints`, the
per-resource form of V5): it covers everything V5 covers of a resource, what wire v8 added included (a
template's `description`, a variant's `metadata`, a data example's `key`), so a change to only those is
a change. Every comparison of resources uses it: this one, what a next release changed
(`NextVersionChanges`), a merge's three sides, what a behind release changed, and what an available
release changes. The base side is what the draft recorded holding of its base when that was put in
place (`catalog_versions.base_fingerprints`), else the base's retained content, fingerprinted now;
`release_entries.fingerprint` (V3, stored per release) is not compared any more. A stored baseline
names the function that computed it (`{"algorithm": "complete-v5", "entries": …}`); one stored before,
without it, is as good as none. The upgrade diff from a source compares the same way:
`installed_resource_fingerprints` holds the complete form of the installed release, stored as such a
baseline at register, upgrade and receiving an archive; one stored before (a flat V3 map) is recomputed
when previewing — from the source while it still serves the installed release, else from that
release's retained content — and replaced at the next upgrade.

Everything wire v8 added to a resource is stored, so it survives being written into a version: a
template's `description` (`document_templates.description`), a variant's `metadata`
(`template_variants.metadata`, kept as it came; this suite writes none of its own), a data example's
`key` and a stencil's `readsTemplateData`. A template built back from the rows a release was written
into fingerprints as the release holds it.

A release without those entries (one from 1.x; the 2.0 upgrade removes them) recorded
no baseline, and none can be reconstructed — the manifest snapshot holds the
manifest entries, not the payloads they were built from. While such a catalog
still matches its released fingerprint the working digests _are_ that release's
digests, so the answer stays exact; once it has drifted the query reports
UNKNOWN and says it has no baseline rather than calling everything new. The
next release records one.

**Beside the resources.** A draft has changes when its **content** differs from the release it is
based on (ADR 0034), and the content hash covers more than resources: each catalog it declares it
uses, its default theme, its presets and its license. `GetCatalogResourceChanges` compares those too
(`CatalogSettingDiff`) and lists each that differs as a `CatalogSettingChange` in `settings`, by entry
as a merge names it (`catalogDependency/<alias>`, `catalog/defaultTheme`, `catalog/presets`, and
`catalog/license`, which a merge does not take since the license is catalog-wide here), against the
catalog details and dependencies the base's manifest froze. They are compared as the content hash
compares them: a dependency by its alias and the release it pins (number and fingerprint; a
declaration naming a draft as the release it pins when that draft has nothing to publish, its base,
since what the draft changed itself is the other catalog's change, published along), not by its
registry or namespace, so renaming or mirroring one is no change; the default theme by theme and alias, the own catalog named or not; the
presets in any order. The catalog's presentation (name, description, keywords, presentation,
attributes) is not content, so changing it is not a change of the release. `hasUnreleasedChanges`
counts both, so a draft whose only change is a dependency is something to publish: the draft's page
lists it and offers Publish, and the dialog names it, in the words of the change rows:

| Change                        | Shown as                                                         |
| ----------------------------- | ---------------------------------------------------------------- |
| a dependency added            | Added: Shared (Dependency) — locked to v1.0.0                    |
| one locked to another release | Changed: Shared (Dependency) — now locked to v1.1.0 (was v1.0.0) |
| one naming a draft of it      | … (Dependency) — its draft Next release, published along         |
| one removed                   | Removed: Shared (Dependency) — was locked to v1.0.0              |
| the default theme             | Added / Changed / Removed: Default theme (Catalog setting)       |
| the presets, the license      | Added / Changed / Removed: Presets / License (Catalog setting)   |

The bar's and the overview's counts include them. A draft of another catalog that this one names
counts as having something to publish (and is published along) when only its settings changed too
(`GetCatalogBorrowing`), and `ReleaseCatalogDraft` releases such a draft first rather than pinning
its base. MCP `get_catalog_changes` returns them as `settings` (`entry`, `state`, `name`, and for a
dependency `releaseVersion`, `draftName`, `baseReleaseVersion`). The REST API's `getCatalogChanges`
lists resources only: its `dependenciesChanged` says the declarations differ, and the default theme,
presets and license have no field in the contract yet.

## What an environment uses

Deploying (`DeployCatalogRelease`, contract `deployCatalogRelease`) names a catalog release by its
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
  discarding the next release by making another draft the next release, undeploys it, logged.
- **History.** Every deploy, undeploy and release of a deployed draft is recorded in the environment's
  history (`DEPLOYED`, `UNDEPLOYED`, `RELEASED`, with the version's id, and the draft's name for a draft).
- **Dependencies of a deployed draft** are used as for a release: what its declarations pin is used
  implicitly (`ListImplicitDeployments`); `includeDependencies` deploys those pinned releases along,
  never a draft.
- **In the UI** the environment page marks the release that is _Latest here_, lists the work in
  progress it serves with _Undeploy_, and shows the production mark. A draft's version menu offers
  _Try it on an environment…_ (deploying it), listing production environments without offering them;
  the banner of a draft other than the next release names the environments serving it. _Try a
  document_ on the environment page makes a PDF of a deployed template as the environment serves it,
  with the template's first example data (`EnvironmentHandler.tryDocument` → `PreviewDocument`).
- **Over REST** `deployCatalogRelease` and `undeployCatalogRelease` (`…/deployments/{registry}/{namespace}/{catalogKey}/{release}`);
  deployments and history entries carry `releaseId`, `status` (`released` | `draft`), `version` and `releaseName`.
- **Over MCP** nothing deploys; `preview_document` and `analyze_template_data` with an
  `environmentId` render what the environment serves.

## Merge internals

The merge (package `catalog.merge`) is built so that later work — choosing any entry, merging a
template per variant or per block, an editor merge tool — adds to it rather than replacing it.

- **Inputs** (`MergeInputReader` → `MergeInputs`). Three sides, each a `MergeInput`: its content and a
  `MergeValue` per entry (`type/key`), resources and `CatalogSetting`s alike, each with a fingerprint.
  The draft (ours) is built from its rows; the merge base and the release (theirs) from the content
  each release retained (`ReleaseContentAssembler`). All three are fingerprinted by one function,
  `CatalogFingerprintService.resourceFingerprints`, the complete fingerprint (settings by
  `settingFingerprint`, a digest over a fixed shape), not read from `release_entries`, which for a
  release received from a source holds its publisher's digests. A received release is written into a
  draft as this suite holds content (a default written out, a slot a stencil needs added), so the draft
  is compared with the base _as it holds it_ (`heldBase`): `catalog_versions.base_fingerprints`,
  recorded when its base was put in place or copied in, or `catalog_draft_merges.held_fingerprints`
  for a release it merged; for an older merge base that one descends from, the same wherever the two
  releases are alike; else the base as released. A baseline another function stored is ignored.
- **Merge base** (`MergeInputReader`, `ReleaseLineage.closestIn`). Found by the lineage releases record
  (`catalog_releases.base_version`, `base_fingerprint`, and `catalog_release_merges`), checked against the installed release of that
  number by fingerprint; missing, it is `MergeInputs.missingBase` and the planner classifies nothing.
- **Mergers** (`ResourceMerger`, found by type in `ResourceMergers`). One classifies an entry from its
  three sides (`MergeEntrySides`) and plans what to write for a `MergeResolution` (`MergeAction`:
  keep, write a value, remove). `WholeResourceMerger`, the whole-entry table above
  (`DraftMerge.classifyOne`), serves every type today. `DraftMergePlanner` reads the inputs and
  classifies every entry; the preview shows that plan and the merge applies it, so the two agree.
- **Dependencies, one entry per catalog** (`CatalogSetting.Dependency`, type `catalogDependency`, keyed
  by the catalog's key). Adding, removing or re-pinning a dependency is a change of its own, so a
  release that adds one and a draft that re-pins another merge cleanly; only both sides changing the
  same catalog differently is a conflict. The entry key is the catalog key, not its full name: the
  contract's `resourceKey` holds at most 50 characters, which a catalog key always fits, and a key is
  unique in a tenant; registry and namespace are part of the value. The draft's side reads its own
  declarations (`MergeInputReader.draftInput`), so one naming a draft of another catalog is valued
  by that draft. A baseline recorded while the declarations were one entry (`catalog/dependencies`)
  takes the base's dependency entries in its place (`CatalogSetting.completeBaseline`).
- **References** (`MergeReferences`, `MergeReferenceCheck`). What an entry refers to, as the catalog
  format carries it: a template's theme and the attributes its variants name, the stencils, images,
  fonts and theme overrides in its documents and a stencil's (found by `ResourceReferenceSites`), a
  theme's fonts, an attribute's code list, the default theme. The planner turns them into each entry's
  `needs` (the entries it refers to that the merge changes). The check runs twice in the merge's
  transaction: on the planned result before anything is written, and on the draft as written. A
  reference into another catalog resolves through the declaration of that catalog in the result: the
  release it pins (its retained content) or the draft it names; undeclared, it is refused. Only what
  the merge broke is refused: a reference that did not resolve in the draft before stays the draft's
  own, for releasing to refuse. The default theme is written without declaring its catalog
  (`SetCatalogDefaultTheme.declaresCatalog`), so the merged declarations stand as merged.
- **Merged content** (`MergeResolution.Content`, `MergedContentReader`, `MergeEntryContent`). The
  command takes merged content apart from `resolutions`; the handler reads each into a `MergeValue`
  before planning (the migrator, the portable `ResourceValidator` with the not-found findings left to
  the reference check, the entry's `type` and `key`; for a setting, its value parsed) and resolves
  the conflict to `MergeResolution.Content` holding it. `WholeResourceMerger.plan` turns that into
  `MergeAction.Write(value, ready = false)`. `MergeEntryContent` writes a side in the same portable
  form `GetDraftMergeSides` returns. For a later merge, an entry resolved to merged content is held as
  the release has it, like a kept one (`held_fingerprints`): the draft changed it, so a later change
  of the release to it is a conflict again, not taken silently. Resolutions record it as `MERGED`.
- **Writer** (`DraftResourceWriter`). Applies the actions to the draft: resources through
  `CatalogResourceInstaller` (the import's own per-type code), marked ready, or for merged content
  left without a ready mark (`ReadyMarks.clear`), a rule it breaks there a `ValidationException` on
  its `mergedContent` field; removals refused while
  another catalog or the draft itself uses one; then the dependency entries, taken or dropped in one
  replacement of the draft's declarations (the order of the ones it keeps stays); settings last,
  through `SetCatalogDefaultTheme` and `SetCatalogPresets`.
- **Merges** (`catalog_draft_merges`). One row per release a draft merged: its merge base, the
  resolutions chosen, what the draft then held of the release (`held_fingerprints`), who and when.
  `CatalogDraft.merged` reads it, so the draft contains the release and is not behind it. Copying a
  draft copies its rows; moving the next release onto a new base (releasing it, or a release put in
  place) clears them. Releasing the draft first records them as the release's merge parents
  (`catalog_release_merges`, `release.metadata."app.epistola.suite".merged`), so what continues on the
  release still contains them.
- **Lineage** (`ReleaseLineage`). Per release its base and merge parents, read once per catalog.
  `containedBy` (a draft's base and merges, closed over parents), `closestIn` (the merge base: the
  closest release on the merged release's line, breadth first, base before merges, that the draft
  contains, with the fingerprint the line names it by) and `behind` all work from it.
- **Token.** `DraftMergePlanner.token` digests the release, the merge base (or the missing one) and
  every fingerprint of the three sides. `PreviewDraftMerge` returns it (REST `token`), the UI, MCP and
  REST (`expectedToken`) send it back, and the merge refuses another (`MergePreviewOutdatedException`,
  REST 409 `merge-preview-outdated`).

To merge a type differently, add a Spring bean implementing `ResourceMerger` whose `types` names it:
`classify` returns the `MergeResource` the preview shows (its outcome decides whether a resolution is
asked for), and `plan` returns a `MergeAction.Write` with the value to write, which can be content
combined from both sides (`ready = false` for anything not released as is). A new kind of resolution
is a new case of `MergeResolution`, as `Content` is.

## Everyday work continuing from parallel work

Which draft is the next release is only a default: any draft is released as it is. There is no
separate operation to move the mark. Parallel work takes it over when it is published for everyday
work to continue from (`ReleaseCatalogDraft.continueEverydayWork`, `NextVersionHandOver`): the
release is made, and the draft continues on it as the next release, as the next release does on its
own release. The draft that was the next release is discarded with its changes (held resources and
other catalogs' drafts naming it refuse that, as discarding does) or kept as parallel work under
`keepPreviousAs`. Nothing is merged. A name in use, or a refused discard, refuses the release too.

In the UI the publish dialog of parallel work asks _After publishing_: everyday work carries on in the
next release (the default), or continues from this version, keeping or discarding the next release.
MCP `release_draft` takes `continueEverydayWork` and `keepPreviousAs`. REST has neither: the next
version is this suite's convention, not the contract's.

## Exporting a release

`ExportCatalogZip` exports a **release**: the one named by `version`, or without one the highest
release. It hands over that release as it was released, rebuilt by `ReleaseContentAssembler` from the
content it retained, carrying the version and timestamp it was cut with and named by its content hash (V6, whatever fingerprint it was cut with), whatever the
drafts have done since, and its id, metadata and base (`release.releaseId`, `.metadata`, `.base`;
completed from the release's row for a release recorded before it said them). An installed release
exports the content it was installed with. The export
dialog offers the versions `ListRetainedReleases` reports, which is the same set the command will
accept, so the offer cannot fail at download. A catalog with no release has nothing to export.

`requirePublishable` does not run for a release. It describes a draft; the release passed it when it
was cut, it is immutable, and a rule that has tightened since must not retroactively make a
published release unexportable. The content _is_ checked against its recorded content hash (or, for a release without one yet, its fingerprint) and a mismatch refuses the export
— it should be impossible, and if it happens the retained content has been altered.

A version the catalog does not have refuses with `CatalogReleaseNotFoundException` rather than
quietly substituting the working copy.

### With what it uses

`ExportCatalogZip(includeDependencies = true)` carries the releases the export pins inside the
archive, under `dependencies/` (contract ADR 0004), followed transitively; the bundled `system`
catalog is left out, as every installation has it. It is how a catalog reaches an installation that
receives catalogs as files. Refused while a pinned release is not here. The export dialog has
_Include the catalogs it uses_; a published version's page has _Export with what it uses_.

Importing one receives every carried release first, creating its catalog when the tenant lacks it;
one the tenant has at that release with the same content hash is left alone, and one with another
content hash, or another catalog under its key, refuses the whole import
(`CatalogImportConflictException`, REST `conflict`, 409). The catalog is then received as asked.

## Importing installs a release

`ImportCatalogZip` receives the archive's release (contract ADR 0004, ADR 0032 section 4). Every
import takes the same path; none names a type.

- **A catalog the tenant does not have** is created as the archive names it (registry, namespace,
  key, name), with the caller's source (an Exchange or registry install) or none (a file). Its release
  is recorded, and its next release is on it. An archive from before wire v8 names no registry or
  namespace, which are its name, so the importer never decides them: it is refused
  (`CatalogNameRequiredException`) until the caller names both. The web UI asks, proposing the default
  registry and the tenant's namespace; REST `importCatalog` takes them as `registry` (`~` for the
  default) and `namespace`, 400 without. Installs whose source names the catalog (the Exchange, a
  registry, a catalog's source, a snapshot) give it that name. A v8 archive names its own: given
  ones must match, since an import never renames (`CatalogNameMismatchException`, REST 400).
- **A release keeps what it says it is** (wire v8, `ReceivedReleases`): its id (`release.releaseId`)
  becomes its id here, so it is the same release everywhere; one another release (or a draft) of the
  catalog has here refuses the import (`CatalogReleaseTakenException`, REST 409 `release-number-taken`: one id names one release).
  Its metadata is kept, every namespace as it came. This suite's namespace
  (`{"app.epistola.suite": {"renderingDefaultsVersion": 6}}`) says which rendering defaults it renders
  with: one this installation lacks, newer or unknown, refuses it before anything is written
  (`ReleaseRenderingDefaultsTooNewException`, REST 400 `catalog-rendering-defaults-too-new`; the import
  dialog says to upgrade Epistola); without it, it renders with the current defaults. Its base
  (`release.base`) is recorded (`base_version`, `base_fingerprint`), for finding a merge base. Every
  release this suite writes says the same of itself, in its snapshot and every archive of it (an
  export, the Exchange, a registry), and none of it moves its fingerprint.
- **A release says who released it.** One cut here records the user (`released_by`) and this
  installation (`released_by_installation`, the installation id in `app_metadata`), and names the
  installation in its metadata (`app.epistola.suite.releasedByInstallation`). A received release
  records the installation it names, and nobody here; passed on, it still names the installation
  that cut it. The release page says "Released here by <user>" or "Released by installation <id>"
  (`GetCatalogReleaseDetail.releasedByLine`); a release recorded before 2.0 wrote them says neither.
- **The same number with the same content hash** is the release the tenant has: nothing changes
  (`newlyInstalled = false`), also in the tenant that released it. The same number with other content
  is refused.
- **A lower number than the catalog's highest release, or a next release with changes of its own**
  (`NextVersionChanges`; everything a next release without a base holds counts as its own): the
  release is recorded beside the others from its archive, and the next release stays, behind it, to
  be merged.
- **Otherwise the next release moves to it** (a fast-forward): its content is replaced by the
  archive's, resources the release no longer has are removed (a cross-catalog use of one refuses the
  import first), the release is recorded, and everything it put in place is marked ready. A resource
  that fails to install aborts it without advancing anything.

`intoDraft` (load an archive into a draft, `TEMPLATE_EDIT`: starting a draft from a release) and
`restore` (snapshots) are internal, and fill the version the transaction targets (`DraftFillMode`
`MERGE` or `REPLACE`). Each resource is written by `CatalogResourceInstaller`, which a merge uses
too.

A tenant snapshot exports each catalog's next release as a working copy, labelled with its highest
release or `0.0.0`; that is the only export of a draft. From snapshot schema 3 it also carries, per
catalog, where it receives releases from (`source`: its URI and auth type; never the credential, as
a snapshot carries no secrets, so a source that needs one is given it again), the release it
received last (`installed`), the release its next release is on (`nextBase`), and its releases
(`releases`: number, id, fingerprint, base, when and by whom, notes), each with its archive at
`catalogs/<key>/releases/<version>.zip`. A restore records each release beside the others as it says
it is (`ImportCatalogZip.restoreRelease`), fills the next release from the working copy, and puts
it back on its base, so the restored catalog renders, merges and releases on as it did. Every member
is optional: a schema 2 snapshot restores with no sources and no releases, as before. The rolled-up
snapshot fingerprint covers the releases too, so a new release makes a new backup.

## Publishing a release to Epistola Exchange

A release cut here can also create a durable Exchange publication
outbox entry in the same database transaction. The outbox retains the exact ZIP
for that immutable release; Exchange availability never determines whether the
local release succeeds. Imported releases suppress publication, and an existing
unchanged current release can be queued explicitly. Setting precedence,
namespace binding, status transitions, and retries are specified in
[`catalog-exchange-publication.md`](catalog-exchange-publication.md).

## Importing a ZIP

An archive is a release, and importing it receives it as [above](#importing-installs-a-release). An
archive of an older wire schema that a migration can bring up to date is migrated, after the web UI
asks to confirm (`CatalogMigrationConfirmationRequiredException`); REST, Exchange and registry
installs confirm up front. There is no MCP import tool, so import is not an MCP surface.

### Releases from a source

Nothing is upgraded in place (contract ADR 0003). A catalog holds the releases it received side by
side, each an immutable release with its retained content, and installing another adds it, whether
it is numbered above them or below. Its source, a manifest URL (`ManifestUrlReleaseSource`),
Epistola Exchange (`ExchangeCatalogReleaseSource`) or a registry, offers releases; a catalog without
a source (made here, or imported from a file) has none to ask (`CatalogHasNoSourceException`).

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
  other content is refused (409 `conflict`).
- A source that cannot be reached answers 503 `catalog-source-unavailable` (with `Retry-After` when
  it gave one), one that answers badly 502 (`CatalogSourceUnavailableException`).
- **`RegisterCatalog`** adds a catalog from a manifest URL; a tenant that has a catalog under the
  manifest's key already is refused (`DuplicateIdException`), since registering never takes over a
  catalog.

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
| Web UI   | Version column (`v<version>` / `unreleased`); drafts (start, discard, make the next release, publish any with `acknowledgedBehind`)                                                     |
| REST     | Catalog releases: list (drafts with `?status=draft`), create a draft, rename, discard, preview a draft's release number, release a draft (`…/releases/{next, release id or number}/…`)  |
| MCP      | `list_catalog_releases`, `start_draft`, `rename_draft`, `discard_draft`, `preview_release_number`, `release_draft` (with `continueEverydayWork`); `catalogVersion` on the content tools |
| Exchange | `ReleaseInfo.fingerprint` in the manifest; `schemaVersion` 4                                                                                                                            |

## Roadmap

- **Phase 2 — upgrade diff/preview.** _Backend landed._ The diff for a
  catalog with a source is **source-vs-source**, not snapshot-vs-installed: the
  per-resource source-side digests are captured into
  `catalogs.installed_resource_fingerprints` (JSONB) at `RegisterCatalog` /
  `UpgradeCatalog`, exactly alongside `installed_fingerprint` and with the same
  provenance (computed from the source manifest, never publisher-authored).
  `PreviewCatalogUpgrade` re-fetches the manifest, recomputes the same
  per-resource digests, and classifies every `(type, slug)` as
  ADDED / REMOVED / CHANGED / UNCHANGED — so a CHANGED verdict means the
  _publisher_ changed that resource, never install round-trip noise. The
  per-resource digest is the complete form
  (`CatalogFingerprintService.resourceFingerprintsFromSource`); cross-catalog conflicts on
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
