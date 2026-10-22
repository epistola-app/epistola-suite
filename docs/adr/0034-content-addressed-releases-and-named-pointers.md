# ADR 0034: Releases are content-addressed; names point at them

- **Status:** Proposed (direction agreed 2026-10-22; implemented in phases, 1 to 3 done, 4 in part)
- **Date:** 2026-10-22
- **Deciders:** Epistola team
- **Tags:** catalog, releases, identity, namespaces, registries, exchange

## Context

ADR 0032 made releases move between installations through the Exchange, container registries and
files. Working through who makes and uses catalogs shows that naming, ownership and location are
tangled, and that the tangle blocks real situations:

| Situation                                                                                             | What it needs                                                                                                |
| ----------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| test → acceptance → production; production never reaches test                                         | the same release in every installation, whatever route it took                                               |
| a supplier publishes on the Exchange, municipalities install                                          | one owner issues releases; many receive them                                                                 |
| Exchange catalogs on the organisation's own container registry; production never reaches the Exchange | a release served from somewhere other than where its name came from (a mirror)                               |
| a municipality fixes something in a supplier's catalog                                                | a local release that does not collide with the supplier's numbers, and the supplier's next release merged in |
| two suppliers both deliver `letters`                                                                  | the same key twice in one tenant, told apart by namespace                                                    |
| departments as namespaces of one tenant, or as tenants sharing a namespace                            | either; several namespaces per tenant, several tenants per namespace                                         |
| a SaaS installation with many organisations                                                           | a tenant cannot issue releases under another organisation's namespace                                        |
| an air-gapped government body                                                                         | names that are unique without asking a central party                                                         |
| a catalog made before its owner claimed a namespace, a reorganisation                                 | a different name later without breaking what refers to it                                                    |
| release channels (stable, latest), forks                                                              | several names, or a new name, for the same content or line                                                   |

What stands in the way today:

- **The name is the identity of a release.** The release fingerprint (V5) covers the catalog's own
  registry and namespace and those of every dependency (`CatalogCanonicalizer`), and pins name a
  catalog by registry, namespace and key. Giving a release another name changes its fingerprint and
  every reference to it, so a mirror under another name, a promotion from a provisional name, a
  channel or a rename are all new releases.
- **The first part of a name, "registry", reads as a location.** It is where the namespace was
  granted, not where a release is fetched from, which is what makes mirrors awkward.
- **Anyone who can change a catalog can number releases of it.** Collisions are detected afterwards
  ("one number names one release"); with several organisations they are structural.
- **A new catalog's namespace is derived from the tenant key,** which in a shared installation may
  be another organisation's real namespace.
- **The suite tells catalogs apart by key alone** (`UNIQUE (tenant_key, catalog_key)`, marked in its
  migration as "until named"), although the database also enforces the full name.

## Decision

Separate four questions, and give each its own answer.

| Question                   | Answered by                                                  |
| -------------------------- | ------------------------------------------------------------ |
| Which release is this?     | its **content hash**                                         |
| What is it called?         | a **name**: authority / namespace / key, and a version       |
| Who may give it that name? | the **holder of the namespace**, by a **binding** they issue |
| Where do I get it?         | a **release registry** (location), or a file                 |

### 1. A release is identified by its content

A release's identity is a hash over its content: its resources, its catalog-level content (default
theme, presets, attributes, …) and its dependencies as pinned hashes — **not its own name and not its
release metadata** (number, id, base, merges, who released it, producer metadata). This is a new
fingerprint algorithm (V6) in the contract; V5 and earlier remain verifiable for releases that carry
them. Two releases with the same hash are the same release, wherever they come from, and a release
fetched by its hash from any registry, mirror or file verifies itself.

### 2. A name points at a release: bindings

A **binding** says `authority/namespace/key @ version → hash`. Releasing creates one; installing,
mirroring and moving carry them along. A binding is what a signature signs (phase 6). Names stay what
people, deployments, version ranges and "is there something newer?" work with; each resolves to a hash
and records it.

- **The name.** `authority / namespace / key`:
  - the **authority** is a domain: the Exchange's (`exchange.epistola.app`) for namespaces it grants,
    or an organisation's own (`gemeente-x.nl`). A domain is unique without a central party. It is the
    part of the name contract ADR 0003 calls `registry`; it never means a location.
  - **namespaces are flat** (`acme`, `acme-hr`); an organisation may hold several.
  - **within a tenant, namespace + key is unique** (an application rule over the database's full-name
    constraint, which stays), so people and addresses use `acme/letters`. Two authorities granting
    the same namespace token to one tenant is refused; the rule can be relaxed to the full name later
    without a migration.
- **Who may issue which binding:**
  - the **holder of the namespace** issues normal numbers (`acme/letters @ 1.3.1`);
  - **anyone else** — a municipality patching a supplier's catalog — may only issue a **patch** binding
    labelled with a namespace _they_ hold: `acme/letters @ 1.3.1-municipality.1`. It sorts above 1.3.0
    and below the holder's 1.3.1, so the holder's next release supersedes it and merges it in. Numbers
    cannot collide: the holder never uses the label, the patcher never issues plain numbers. A patch
    binding is never published under the holder's authority.
  - **channels** (`acme/letters @ stable → hash`) are bindings too (a later phase).
- **One place per catalog issues its bindings.** A tenant of an installation holds the authorship of a
  catalog; the others receive. Authorship can be transferred deliberately (it is what the Exchange's
  authoritative connection already enforces for publishing).

### 3. Namespaces are granted to tenants

An installation records which tenants may use which namespaces: granted through the tenant's Exchange
connection, or by the administrator for the organisation's own authority. Several tenants may share a
namespace (departments as tenants); a tenant may hold several (departments as namespaces). A new
catalog is named in a namespace its tenant holds, chosen when it is created; nothing is derived from a
tenant key.

### 4. Catalogs refer to each other by alias

A catalog's dependency list maps an **alias** to `{ name, pinned hash }`: the name to find newer
releases, the hash as the pin. Content refers to another catalog by alias and resource key ("theme
`brand` of `shared`"); within a catalog, by key, as now. Moving a dependency to a newer release changes
one line of the list, not the content that uses it, and renaming or mirroring a dependency changes
nothing in the content at all. The registry and namespace qualifiers in references (made to survive a
round trip in October 2026) move into the dependency list.

### 5. Lineage is by hash

A release's base and the releases merged into it (ADR 0032 §5, `release.base`, the merge parents) are
recorded by hash, with the number for people. Lineage then survives renames, mirrors and forks.

### 6. Locations

- A **release registry** is a place releases and their bindings are kept: the Exchange, a container
  registry, later an internal registry between the tenants of one installation. A file is the offline
  route. A container registry stores a release under `<prefix>/<authority>/<namespace>/<key>`, tagged
  by version, and resolves by digest.
- A catalog has a **source** per tenant: the registry it receives new releases from. It can be changed
  ("from now on, from our Harbor").
- **Mirroring** copies releases and their bindings unchanged from one registry to another. It adds
  nothing under a name, so it needs no namespace; the hash proves the content, the signature the
  binding.
- The **Exchange** hosts names of its own namespaces and, once it has verified an organisation's domain
  (a DNS record or a `.well-known` document), names under that domain. An organisation can start under
  its own domain and distribute through the Exchange later under the same name.

### 7. A local authority for names that never leave the organisation

Every installation has the authority `local`. `local/hr/letters` is valid within the organisation's own
installations (moved by file or its own registry) and never published to the Exchange or outside.
**Promotion** to a real name adds bindings under the new name to the same hashes and names the catalog
there in its authoring tenant; because content refers by alias, nothing in any release changes, and
receivers follow the new bindings. Leaving the organisation requires a real name.

### 8. Proof

The hash proves content; a **signature** over a binding proves who named it. Keys belong to namespaces
and are published by their authority (the Exchange for its namespaces, the domain's `.well-known`
otherwise). A tenant can require signed bindings for an authority. Signing is a later phase; until
then bindings are trusted as the installation receives them, and the hash still guarantees the content.

## How the situations land

| Situation                                  | In this model                                                                                       |
| ------------------------------------------ | --------------------------------------------------------------------------------------------------- |
| test → acceptance → production             | one authoring tenant; others receive by registry or file; same hash and binding everywhere          |
| supplier → municipalities                  | the supplier holds `acme` and issues bindings; municipalities' source is the Exchange               |
| own Harbor as mirror                       | releases and bindings copied unchanged; production's source is Harbor; fetched by hash              |
| a municipality's fix to a supplier catalog | a patch binding `-municipality.N`; superseded and merged by the supplier's next release             |
| two suppliers' `letters`                   | `acme/letters` and `bureau/letters`, unique by namespace + key                                      |
| departments as namespaces                  | the tenant holds `acme-hr` and `acme-finance`                                                       |
| departments as tenants                     | they share `acme`; one tenant per catalog holds the authorship                                      |
| SaaS                                       | bindings only in namespaces granted to the tenant                                                   |
| air-gapped body                            | its own domain as authority; files or its own registry                                              |
| provisional names, reorganisation          | `local` and promotion; or new bindings with the old ones kept                                       |
| channels, forks                            | channels are bindings; a fork is a new name whose first release has the original's hash as its base |
| bundled catalogs                           | authority `epistola.app`, namespace `epistola`, supplied by the suite, read-only (ADR 0032)         |

## Alternatives considered

- **Names as identity, as now.** Renames, promotions, mirrors under other names and channels all become
  new releases with rewritten references, and others' changes collide with the owner's numbers.
- **Docker-style tags without content addressing.** Tags fit images because images do not refer to
  each other by name; catalogs do, and their fingerprint covers the name, so a second name is a second
  release.
- **A local namespace with a one-time rename only.** Workable, but every rename rewrites references and
  fingerprints; with aliases and content hashes it costs nothing.
- **Nested namespaces** (`acme/hr`). More expressive, but the contract's namespaces are flat and
  several flat namespaces per organisation cover departments.
- **Forks only, for changes to others' catalogs.** Honest about ownership, but it needs merging across
  catalogs and repointing every user; kept for real divergence, with patch bindings for fixes.

## Consequences

- **Contract (catalog format, before wire v8 is released):** a V6 fingerprint over content only;
  references to other catalogs by alias; dependencies as alias → name + pinned hash; base and merges by
  hash; bindings as the way a name and number attach to a release (in the manifest's release block for
  archives, and as the registries' tags and Exchange listings).
- **Contract (API):** releases addressable by hash as well as by name and number; bindings visible
  (which names point at a release); patch numbers; namespace grants; the Exchange's domain
  verification. Amend contract ADRs 0003 (identity) and 0008 (release registries).
- **Suite:** releases keyed by hash with numbers as bindings; namespace grants per tenant and catalogs
  created only in held namespaces; catalogs told apart by namespace + key, and key-only lookups
  removed; the patch rule in releasing; sources per tenant and mirroring; aliases in content and in the
  editor; signatures later. The storage of ADR 0033 is unaffected in shape: entries already point at
  revisions by digest, and a release's identity becoming a hash fits it.
- **People** keep working with names and numbers; hashes appear where they help (verifying, comparing,
  "the same release as").

## Phasing

1. **Contract:** the V6 fingerprint, aliases, dependencies by hash, lineage by hash, bindings in the
   manifest. _Done_ (contract `feature/v2`, `18813c5`..`15de68a`: aliases, V6, patch numbers, 128
   characters).
2. **Suite:** releases identified by hash; receiving and moving by hash; numbers as bindings.
   _Done_ (22 October 2026, branch `refactor/catalog-releases`):
   - content names other catalogs by alias; the suite declares every dependency under its key
     (`catalog_dependencies.alias`, `catalog_release_dependencies.alias`) and stores references by
     catalog key, which is the alias, until phase 4 lets two catalogs share a key; an archive that
     declares another alias is refused on import (`CATALOG_DEPENDENCY_ALIAS_UNSUPPORTED`), since
     storing it would rewrite its references and so its content hash. Stored qualifiers were
     dropped by a migration, resolving as before;
   - every release records its **content hash** (`catalog_releases.content_hash`, V6): new releases
     are fingerprinted with V6, older ones keep their fingerprint and get the hash from what they
     retained (a startup cluster task, or on first use), unless what they retained no longer
     reproduces that fingerprint, which gets none; `ReleaseIdentity` decides every "same
     release?" question by it, lineage is by hash, pins copy the hash, and every archive names a
     release by it;
   - patch release numbers (`1.3.1-gemeente-x.1`, up to 128 characters) are received and ordered as
     SemVer says; issuing them stays phase 5;
   - `catalog_releases` stays the record of a release under its catalog and number, which is the
     binding; storage is ADR 0033's.
3. **Namespace grants** per tenant; new catalogs only in a held namespace. _Done_ (22 October 2026,
   branch `feat/namespace-grants`; `docs/catalog-versioning.md`, "Who names a catalog"):
   - `tenant_namespace_grants` records each tenant's namespaces by authority, how they came to be
     held (Exchange connection, administrator, existing) and when; a platform administrator grants
     and revokes them on the tenant list's Namespaces page, and a tenant is created with one, in
     which its default catalog is named. One tenant holds a namespace token under one authority,
     as an application rule.
   - **Existing data**: each tenant holds the namespaces its Exchange connection grants, and those
     of the catalogs it authors, by ADR 0032's source rule: no source (so neither supplied by
     Epistola nor installed from the Exchange, a registry or a manifest URL), not a 1.x subscribed
     copy, and not a file another installation released (a release naming another installation and
     none cut here). A release recorded before releases named their installation counts as cut
     here, since every such catalog could be released here until then.
   - **`local`**: any tenant names catalogs under it with a namespace it picks, without a grant,
     since nothing outside depends on it, unless it holds that token under another authority. A
     local catalog is never published to the Exchange. The contract does not carry `local` yet (its
     registry rule wants a domain, in the catalog format and in the API's paths): a local catalog is
     released and deployed here, but not made into an archive (export, container registry,
     snapshot, a draft copied from a release) and not addressable over REST, until the contract
     accepts it. Container registries have no notion of being outside, so nothing more is refused
     there. A tenant created without a namespace names its default catalog `local/default/default`.
   - **Revoking** a namespace that catalogs the tenant authors are named in is refused, naming them;
     a received catalog does not block it. An Exchange grant outlives the connection; publishing
     still checks what the connection grants.
   - Releasing needs the namespace; a received catalog in another namespace is changed here and
     released once the namespace is granted (patch numbers stay phase 5). Receiving needs no grant.
     The REST API has no create operation, so it is unchanged.
4. **Namespace + key** as the unique name in a tenant; key-only lookups removed in steps. _Done in
   part_ (22 October 2026; `docs/catalog-versioning.md`, "Two catalogs with one key"): the database
   holds a catalog unique by namespace and key; every lookup by key goes through
   `epistola_catalog_id`, which takes the namespace the work names (`CatalogNames`) and refuses an
   ambiguous key instead of picking one; UI addresses and MCP `catalogId`s name the namespace. Open:
   aliases other than the key (references still resolve by key), and tenant-wide views keyed by key.
5. **The patch rule** in releasing.
6. **Sources per tenant**, changeable; **mirroring**; container paths with the authority.
7. **Signatures** over bindings; the Exchange's domain verification.
8. Later: channels, the internal registry between tenants, forks with merging across catalogs,
   promotion from `local`.

## Open questions

- ~~Exactly what the V6 hash leaves out besides the own name and the release block (catalog
  description and presentation are content today; are they per release?).~~ Answered by the
  contract (ADR 0003, amendment of 2026-10-22): V6 leaves out the catalog's presentation (name,
  description, keywords, presentation, attributes, publisher, includes) and the wire version; the most
  recently received presentation wins.
- How a binding is carried in an archive and in a container registry (a signed statement beside the
  layer, as cosign does, or in the manifest).
- The signature scheme and key distribution per authority.
- How the Exchange verifies a domain, and what happens when a domain changes hands.

## Related

- [ADR 0032](0032-catalog-sources-releases-and-merging.md): drafts and releases, moved and merged.
- [ADR 0033](0033-reading-any-release.md): reading any release; storage.
- Contract ADRs 0003 (catalog identity), 0005 (merging), 0008 (release registries).
