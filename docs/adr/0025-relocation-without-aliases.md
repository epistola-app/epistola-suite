<!--
SPDX-FileCopyrightText: Epistola Nederland B.V.

SPDX-License-Identifier: AGPL-3.0-only
-->

# ADR 0025: Relocation without aliases, until published content stands on its own

- **Status:** Accepted
- **Date:** 2026-09-22
- **Deciders:** Epistola team
- **Tags:** catalog, relocation, identity, publication, exchange, database
- **Supersedes in part:** [ADR 0014](0014-safe-catalog-resource-relocation.md) (its alias half)
- **Related:** [ADR 0020](0020-where-a-catalog-resource-address-lives.md),
  [ADR 0024](0024-where-a-catalog-resource-identity-is-allocated.md),
  [ADR 0018](0018-durable-catalog-publication-to-exchange.md),
  [ADR 0022](0022-installation-bindings.md)

## Context

ADR 0014 chose to make moving a catalog resource safe with **aliases**: a move leaves its old
address behind as an alias to the resource's stable identity, and every surface resolves that alias,
so a published template that names the old address keeps working. Relocation was built that way in
#869, hardened by a full test pass in #967, and developed alongside publishing catalogs to Epistola
Exchange (#870) and installing from it (#915). None of it had shipped: 1.1.0 is the latest release.

A review of that work in September 2026 concluded that aliases cannot deliver the promise they were
built for — that published work stays correct when the resources it uses are reorganised:

- **They are tenant-local.** An alias keeps an address pointing at a resource inside one tenant. A
  subscriber that installs a later release sees the resource gone, not moved; an export has to
  rewrite every aliased reference on the way out; a snapshot has to carry the aliases with it. The
  guarantee stops at exactly the boundaries catalogs exist to cross.
- **They spread to every surface.** Each place that takes an address needed its own handling: a
  reservation check on six create commands, rewriting on every template write, redirects in the UI,
  a resolution step in REST and MCP, fallbacks in four render-time resolvers, canonicalisation in
  export, planting in snapshot restore, and a release command for the reservation. The test pass
  found 30 defects across them, and the set of surfaces that honoured an old address still differed
  per resource type.
- **They treat a symptom.** A published template version names its fonts, images and theme by
  address and reads them live when it renders. A move is only one way to change what it renders:
  editing, replacing or deleting the resource does the same, and aliases do nothing for those. What
  makes published work safe is published content that retains its own inputs, not a way to keep
  addresses alive.

The schema for aliases existed only in migrations that no release and no published image contained.

## Decision

1. **Withdraw aliases before any release carries them.** The `catalog_resource_aliases` table, and
   the `uq_catalog_resources_typed_identity` constraint that existed only as its foreign-key target,
   are removed from the unreleased migrations `V20260905090000` and `V20260920160936` themselves,
   under SHA-pinned `checkMigrationVersions` exemptions, rather than dropped by a later migration.
   Every consumer goes with them; REST, MCP and generation address handling return to their 1.1.0
   behaviour.
2. **Keep relocation as a crude move** — alpha, off by default, behind `resource-relocation`. A move
   changes the resource's address, rewrites drafts, variant attributes and theme styles, and leaves
   nothing at the old address. Anything that still names it stops resolving, and the address can be
   reused at once. [Catalog resource relocation](../catalog-resource-relocation.md) lists what
   follows a move and what breaks.
3. **Keep the identity model.** Every catalog resource keeps its stable `resource_id`, dependants
   reference it by typed foreign key, the `catalog_resources` registry and its sync trigger keep
   allocating identities and planting them on snapshot restore, and generation history keeps
   `template_resource_id`. This is the foundation for everything below; ADR 0020 stands, and
   ADR 0024 (who allocates an identity) remains an independent proposal.
4. **Leave Exchange publication and installation as they are**: alpha and off by default. Nothing in
   them depended on aliases.
5. **Make published content self-contained before relocation becomes safe again.** That is the
   direction, not yet a design: each step gets its own decision when it is started.

### The direction

Separate what an author edits from what has been published, and separate publishing from deploying:

- A **published template version** retains everything it needs to render — its document model, the
  resolved theme, the font faces and images it uses, the content of the stencils it embeds, and its
  contract — and never resolves a dependency by address again. Activating a version selects that
  artifact; it does not rebuild it.
- A **catalog release** retains its exact contents and the binaries they need, whether or not it is
  ever queued for Exchange, and installing one never rewires an environment by itself.
- Once published work no longer depends on addresses, **relocation returns as an authoring
  operation** over identity-based references: it re-points drafts and leaves published artifacts
  untouched. Following a publisher's move across installations is a separate, optional capability.

Decisions still open, to be settled with the step that needs them: the promise made about external
generation addresses; whether draft references track their dependencies or keep the revision they
selected; retention of old artifacts and releases; whether an archived version may be reactivated;
licensing of dependencies packaged into a release; the wire format for self-contained releases; how
existing published versions without retained inputs are migrated; and whether several templates
are ever deployed as one unit.

## Considered options

- **Finish aliases.** Close the remaining per-surface gaps and add a portable relocation handoff for
  subscribers. Rejected: it grows the mechanism that did not hold, and still leaves published work
  exposed to every change other than a move.
- **Drop the table with a new migration.** Keeps the rule that a merged migration is never edited.
  Rejected here: no installation ever ran these migrations, and the next release would otherwise
  ship a table created and dropped in the same upgrade. The edits are exempted individually by
  digest, as #953 was, and only because they are provably unreleased.
- **Remove relocation altogether.** Rejected: behind an alpha toggle, a crude move is still useful to
  an operator who knows what uses a resource, and its planner, rewrites and cycle guard remain
  sound.
- **Remove the identity registry as well.** Rejected: with aliases gone it no longer serves address
  lookups, but it is still what allocates every `resource_id` and what lets a snapshot restore keep
  identities. Moving allocation into the application is ADR 0024's question, not this one's.

## Consequences

- The next release creates no alias schema, and its migrations contain only what is kept. The
  re-keying onto `resource_id` still needs the maintenance window described in
  [Upgrades](../upgrades.md).
- Local and test databases that ran the unreleased migrations fail Flyway's checksum validation and
  must be reset (`./gradlew :apps:epistola:resetLocalDb`).
- A crude move breaks what still names the old address: published versions that use a moved font
  fail to render and a moved image renders without the image; bookmarks, REST and MCP callers get
  "not found"; queued generation for a moved template fails; and a catalog whose latest published
  versions name a moved resource cannot be exported, released or snapshotted until they are
  republished. The move preview says how many published references are affected.
- A subscriber still does not follow a publisher's move: a resource the publisher moves or removes
  disappears on upgrade, under the existing in-use checks.
- The parts of ADR 0014 that describe aliases no longer hold; its amendment lists them. ADR 0020's
  reasons for the registry narrow to allocation and restore, and ADR 0022's draft reliance on "the
  alias mechanism" for bound resources is void.

## Current status

What the next release contains, as of this decision:

| Area                        | Status                                                                                                                                                                          |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Resource identity           | Included. Every resource is keyed by a stable `resource_id`; the upgrade needs a maintenance window.                                                                            |
| Relocation                  | Alpha, off by default. A crude move; nothing is left at the old address.                                                                                                        |
| Old addresses               | Not resolved by any surface; a stencil's page ignores the catalog in its URL (see the relocation guide).                                                                        |
| Published template versions | Retain their document model, their resolved theme and the content of embedded stencils; fonts and images are still read live by address, with font integrity checked at render. |
| Exchange publication        | Alpha, off by default. A release's archive is retained until Exchange accepts or rejects it.                                                                                    |
| Exchange installation       | Alpha, off by default. An installed catalog is a read-only mirror, upgraded as one unit.                                                                                        |
| Self-contained publication  | Not started. The direction above.                                                                                                                                               |

## References

- [Catalog resource relocation](../catalog-resource-relocation.md)
- [Publishing catalogs to Epistola Exchange](../catalog-exchange-publication.md)
- [Installing catalogs from Epistola Exchange](../catalog-exchange-installation.md)
- [Catalog resource identity migration](../catalog-resource-identity-migration.md)
