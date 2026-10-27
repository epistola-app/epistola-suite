# ADR 0032: Catalogs are drafts and releases, moved between installations and merged

- **Status:** Accepted (merging and the registry route ship as alpha) — superseded in part by ADR 0038
- **Addendum (2026-10-27):** Superseded in part by [ADR 0038](0038-catalogs-in-2-0.md) (2026-10-27): §9 (the table fold, done the other way round) and the storage terms; merging, the Exchange, the registry and files stand.
- **Date:** 2026-10-21
- **Deciders:** Epistola team
- **Tags:** catalog, releases, exchange, installations, merging, database

## Context

A catalog is used in four ways, and each has to be obvious to someone who is not technical:

1. **Release a new version** of a catalog: edit the draft, press _Release_, get a frozen release.
2. **Deploy a release** to an environment of an installation, and roll back to an earlier one.
3. **Move a release between installations.** A typical organisation has a test, an acceptance and a
   production installation. Production can never reach test. It can reach a registry it already
   pulls from (a container registry, or the Exchange).
4. **Use a catalog from the Exchange**: install it, deploy its releases, take in its new ones.

Some organisations never change anything in production; others allow it. So a catalog can be
changed in more than one installation, and those changes have to come back together.

### What stands in the way today

The suite gives every catalog a **type**. An `AUTHORED` catalog has drafts and is released here; a
`SUBSCRIBED` one is a read-only copy of a release installed from elsewhere, kept in its own
`installed` row of `catalog_versions` and replaced on upgrade. That type is chosen when a catalog is
created or imported, and it decides everything else. Checked against the four uses (October 2026):

- **Deploying side by side works.** Every release renders from its own snapshot (`release_entries`
  → `resource_revisions`), never from the editable or installed copy (`ReleaseRenderSource`), so an
  older and a newer release of any catalog run side by side in two environments, and an upgrade
  leaves deployments alone. Drafts render from their editable content, as they should.
- **Moving between installations is a manual ZIP round trip**, and the import asks whether the
  catalog becomes `AUTHORED` (the default) or `SUBSCRIBED`. The default makes production a second
  author. No installation can pull from another.
- **A release is not quite the same everywhere.** The receiving installation stamps its own
  `rendering_defaults_version` and gives the release a new release id; the system catalog's fonts
  are not part of its release or fingerprint; a locally created catalog's namespace silently
  defaults to the tenant key and its registry to a setting.
- **The Exchange already has the rules we want**: only the namespace's organisation may publish, one
  installation per catalog is its publisher, and a number is never published twice. It refuses any
  number below the highest published one, so a fix to an older line cannot be published.

The type is the wrong question. Whether a catalog can be changed is a property of the installation
(do we allow editing here?), and where its releases come from is a property of the release.

### What other systems do

Package registries (npm, Maven, Docker) keep source and artifact apart: the source is edited
somewhere, an immutable `name@version` is published, and every consumer, the author's own
production included, installs that artifact. GitOps promotes one artifact digest through
environments. Salesforce has the same split as our types (managed and unmanaged packages) and moved
to source-driven development because moving changes between sandboxes was painful. WordPress shows
the opposite: content, artifacts and environment configuration in one database make staging to
production its best-known pain.

Git has the model we need for changes made in more than one place: content stored by hash, trees
that name a state, branches, parents, and three-way merges against a common base. **Our storage
already has most of it:**

| Git    | Epistola                                                         |
| ------ | ---------------------------------------------------------------- |
| blob   | `resource_revisions`: a resource's content, by digest            |
| tree   | `release_entries`: which revision of each resource a release has |
| tag    | `catalog_releases`: an immutable release with its fingerprint    |
| parent | a draft's `base_version`                                         |
| branch | a draft: the next version, or longer-running work with a name    |

## Decision

### 1. Git's model, in our own database

We adopt git's model, not git. Catalogs stay in the database, which every feature queries and every
node of a cluster shares. Storing them in git repositories was considered and rejected (see
Alternatives). What we add to complete the model is a merge, and a record of which releases a draft
has taken in.

### 2. No catalog types

A catalog is its **releases** and its **drafts**. Every catalog has a **next version**, the draft
everyday work goes to, started from a release (its base). There is no `AUTHORED` or `SUBSCRIBED`:

- **Whether a catalog can be changed** depends on the installation (section 3) and on one fact about
  the catalog: a catalog **the suite itself supplies** (the system catalog, the demo catalog; a
  `classpath:` source) is never changed, because the suite replaces it on every upgrade.
- **Where a catalog's releases come from** is its source (`catalogs.source_url`): the Exchange, a
  registry, a manifest URL, the suite, or nothing for one that is only released here or imported
  from files.

A catalog that was `SUBSCRIBED` becomes a catalog whose next version has no changes of its own.

### 3. Editing is a switch per installation

`epistola.catalog.editing.enabled` (default `true`). With it off, an installation is **run-only**:
no draft can be started, changed, discarded or released, and nothing offers to. Receiving releases,
deploying them and rolling back work as before. A typical production installation turns it off;
one that allows changes in production leaves it on.

### 4. Receiving a release is a merge into the next version

Every way a release arrives (Exchange, registry, file, the suite's own catalogs at start-up) does the
same two steps:

1. **Record the release** beside the catalog's others, with its snapshot, at its number. The same
   number with other content is refused, as it is today ("one number names one release"). The
   receiver always recomputes the fingerprint from the content; a label or tag is never trusted.
2. **Bring the next version along**, when the release is newer than its base:
   - next version **unchanged** since its base: it moves to the new release (a fast-forward). This is
     what upgrading a subscribed catalog did;
   - next version **changed**: it is _behind_ the release, as `ReleaseCatalogVersion` already
     reports, and the person merges it (section 5). Until then, releasing it asks for that.

Other drafts are left alone; they can be merged the same way.

### 5. Merging, per resource, from what is kept (alpha)

A draft based on release `B` takes in release `T`. Per resource, from three fingerprints that the
suite already keeps (base and theirs from `release_entries`, ours from the draft):

| Ours vs base | Theirs vs base | Result                                       |
| ------------ | -------------- | -------------------------------------------- |
| same         | same           | unchanged                                    |
| changed      | same           | keep ours                                    |
| same         | changed        | take theirs (added, changed or removed)      |
| changed      | changed        | same change on both sides, or a **conflict** |

A conflict is resolved per resource by choosing ours or theirs; merging inside one resource is
later work. Applying the merge copies theirs into the draft for every resource that takes it, and
records that the draft has taken in `T`, so it is no longer behind it. A merge with no conflicts and
no changes of ours is the fast-forward of section 4.

### 6. Release numbers: one number, one release

A number names one content everywhere. That is checked where releases meet: an import refuses a
known number with other content, the Exchange refuses a number published before, and a registry
does when its tags are immutable (the suite checks before it publishes, since many registries
overwrite by default). Two installations that release the same number independently are therefore
**detected**, not prevented: the second one cannot be received or published, and its changes come
back as a merge and a new number. Organisations that need prevention release in one installation,
or publish through the Exchange.

### 7. One way to receive releases, three routes

The person sees one action, _Get a release_, and then deploys it. The routes:

- **The Exchange**, for sharing and for organisations with a namespace there.
- **A container registry** (OCI artifacts, alpha), for moving between one's own installations.
  Production already pulls images from one. A release is pushed as an artifact named
  `<registry>/<namespace>/<key>:<version>`, with the catalog archive as its layer.
- **A file**, for installations with no network path between them.

The suite does not pull from another installation directly.

### 8. A release is complete and the same everywhere

Follow-ups, each its own change:

- The release carries its **rendering defaults version** as suite metadata, and its **release id**
  (unique within the catalog's name), both kept on receipt. Contract change.
- A catalog's **registry and namespace are explicit**: confirmed when it is created, never derived
  from a tenant key or a setting on the way; an archive without them is imported only when the
  importer names them.
- The **system catalog's fonts are resources of its release**, inside its snapshot and fingerprint,
  and the suite keeps every system release it shipped, so a release that pins an older one still
  renders. A release that pins a newer one than the receiver has is refused with "upgrade the suite
  first".
- The **Exchange accepts any number not yet published**, so fixes to older lines can be shared.
- The contract's `CatalogType` (REST `CatalogDto.type`, `importCatalog`'s `catalogType`) is removed.
  Until it is, the suite reports a catalog with a source as `SUBSCRIBED` and any other as
  `AUTHORED`, and ignores the type on import.

### 9. Storage

Drafts live in the content tables; releases live in their snapshots. The `installed` kind of
`catalog_versions` row goes away, because a catalog's browsable content is its next version. The
remaining step, folding `catalog_versions` into `catalog_releases` so a release is one row from
draft to released, is a rename that follows this decision (the one-table option considered below).
[ADR 0033](0033-reading-any-release.md) carries the consolidation further: reading any release the
same way, and, as its option 4, drafts stored like releases (entries pointing at revisions), after
which the content tables go too.

## Alternatives considered

- **Keep the types, add a fork.** Only the authoring installation changes a catalog, others copy it
  under a new name to change it. Clear, but it does not serve organisations that change things in
  production, and every user of the original has to be pointed at the copy.
- **Store catalogs in git.** History, branches and transport for free. But git merges lines and our
  content is JSON trees, so the merge would be ours anyway; every query, the clustering and the
  backups would have to deal with a second store; and moving the data there would migrate all of
  it. Rejected.
- **Git as an extra route** (push releases as tags, drafts as branches, pull from a git server), as
  Grafana's Git Sync does for dashboards. Compatible with this decision and left for later.
- **Storage alone.** The first version of this ADR weighed three table layouts: two tables renamed,
  one table with today's lifecycle, or one where a draft row becomes its release (moving every
  content row on every release). The second remains the plan for section 9's last step; the
  storage question turned out to follow from the model, not the other way round.

## Evidence

- **Side by side:** `ReleaseRenderSource` reads only `release_entries` and retained revisions;
  `DeployCatalogVersion` accepts any recorded release; `UpgradeCatalog` touches no deployment.
  Tests cover authored catalogs (`ReleaseRenderSourceTest`, `CatalogDeploymentTest`); none yet
  deploys two releases of a received catalog and compares what they render.
- **Merge spike** (two tenants as two installations, themes): base 1.0.0, theirs 2.0.0 added `w`,
  changed `x` and removed `z`; ours changed `x` and `y`. The table above classified `w` and `z` as
  theirs, `y` as ours and `x` as a conflict; applying theirs through the existing
  release-into-draft import left the draft equal to theirs for `w`, `x`, `z` and ours for `y`. The
  release that followed was refused as _behind_ 2.0.0, the hook the merge plugs into.
- **Registry spike** (CNCF distribution 2 and 3, zot, a 170-line JDK-only client): push, pull with
  verified bytes, metadata from the manifest alone, identical digests across registries, shared
  fonts stored once. Tags are overwritten silently by default; zot refuses it with a create-only
  policy, Harbor, ECR, ACR and Docker Hub document an option for it, GHCR has none. A tampered
  archive kept its original fingerprint label, so labels are not evidence.

## Consequences

- People see releases, drafts and environments, and one _Get a release_ action. Import no longer
  asks for a type.
- A run-only installation is a configuration choice, not a convention.
- An upgrade of a received catalog is a merge that usually fast-forwards; a catalog changed locally
  shows what conflicts instead of silently losing or keeping changes.
- Data: `catalogs.type` is no longer read and stays in place until a major release removes it. A
  former subscribed catalog's `installed` row becomes its next version on its installed release.
- The behaviour is new in 2.0, so it can still change; merging and the registry route are labelled
  alpha in the CHANGELOG and the UI.

## Related

- Contract `feature/v2`: ADR 0001 (hotfix releases), 0002 (draft and released versions), 0003
  (catalog identity). This ADR replaces the hotfix route for changing someone else's catalog with
  local changes and a merge.
- [`docs/catalog-versioning.md`](../catalog-versioning.md): the version model as built.
- PR #1117: the REST layer says release.
