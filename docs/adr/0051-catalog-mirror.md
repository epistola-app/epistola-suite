# ADR 0051: Catalog mirror: a custody copy for release checks

- **Status:** Proposed (pilot built)
- **Date:** 2026-11-05
- **Deciders:** Epistola team
- **Tags:** support, catalog, releases, mirror, consent, outbound-traffic
- **Related:** [ADR 0034](0034-content-addressed-releases-and-named-pointers.md) (content-addressed
  releases and names; §7 amended),
  [ADR 0038](0038-catalogs-in-2-0.md) (catalogs in 2.0),
  [ADR 0050](0050-support-connection.md) (the support connection),
  [`docs/support-protocol.md`](../support-protocol.md) (the wire protocol, v1),
  [`docs/catalog-mirror.md`](../catalog-mirror.md) (the feature)

## Context

Before a suite version ships, Epistola wants to render the releases customers actually run against
it, so a change that breaks a customer's letters is found before the customer upgrades (ADR 0050).
Upgrading did this from tenant snapshots until 29 October, when both were withdrawn: a snapshot
exported working copies, which 2.0 refuses for a catalog that depends on a release never made.

For such a check Epistola needs a copy of the releases, which of them are deployed where (production
first), and what they depend on. 2.0 makes that cheap to describe:

- **Every release is identified by its content hash** (ADR 0034 §1, the V6 fingerprint), and every
  released catalog version records one (ADR 0038).
- **A binding names a release**: `registry / namespace / key @ version → hash`, a released catalog
  version and its catalog.
- **Any release can be exported again** as the archive the suite exchanges, from its stored content.

The suite already sends releases out: to Epistola Exchange (`docs/catalog-exchange-publication.md`)
and to container registries (`docs/catalog-registry.md`). Both are ways of **distributing** a release
under a name.

## Considered options

1. **Exchange with automatic publication**, or a separate, private Exchange for support. Rejected:
   Exchange is an **authority**. It issues permanent names in its namespaces, distributes releases to
   anyone who installs them, keeps a published release for good, and checks namespace bindings before
   it accepts one. A tenant's `local` catalogs are never published at all (ADR 0034 §7). A health
   check needs none of that: it needs Epistola to hold a copy, under the support contract, and nobody
   else to see it.
2. **Push each release as it is made**, from an event. Rejected: releases and deletions publish no
   events today, a missed or failed push leaves a gap no one notices, and the releases that existed
   before the tenant opted in would need a separate backfill.
3. **A general mirror or backup** of the installation's catalogs. Out of scope: backup and disaster
   recovery need guarantees (completeness, drafts, retention after deletion, restore) that a check
   does not, and are the installation's own responsibility.
4. **Chosen: a mirror**, a custody copy that the suite reconciles with what each tenant shares.

## Decision

### 1. A mirror, not an exchange

|                    | Exchange                                              | Mirror                                                          |
| ------------------ | ----------------------------------------------------- | --------------------------------------------------------------- |
| **Names**          | issues them: namespaces under its authority, bindings | names nothing; carries the installation's own bindings as data  |
| **Readers**        | anyone who installs                                   | Epistola, under the support contract with this installation     |
| **Permanence**     | a published release stays, immutable                  | held while shared; deleted when the tenant stops sharing it     |
| **What drives it** | an author publishing one release                      | the suite reconciling everything a tenant shares, on a schedule |
| **Who chooses**    | the namespace holder, per release                     | the tenant, per catalog                                         |
| **`local` names**  | never                                                 | yes (§5)                                                        |

Nobody resolves a name against the mirror, and nothing is distributed from it.

### 2. Reconcile the complete state, per tenant

Like `git push --mirror`, which sends every ref and only the objects the other side lacks:

- The suite sends, per tenant, the **complete set of bindings** it shares now: name, version, content
  hash, release time, the environments it is deployed to, and the dependencies it pins (names and
  hashes).
- The service replaces what it held for that tenant with the set, deletes every release of this
  installation that no binding references any more, and answers with the **hashes it lacks**.
- The suite uploads those releases' archives, one per hash.

This is self-healing: a failed round, a deleted or renamed catalog, a release made while the service
was down, all come right at the next round. The first round is simply a large difference, so there is
no backfill. Because releases are content-addressed and never change, an unchanged tenant costs one
exchange of the list and moves no archive.

In the pilot a release installed from Exchange is uploaded like any other when the mirror lacks it.
Epistola could take those from Exchange by hash instead; that is later (§3).

### 3. Hashes are compared within one installation

Everything the mirror holds is scoped to the installation that sent it. A hash is only ever compared
with the same installation's releases, so the "missing" answer cannot tell one customer whether
another holds some release. Deduplicating against releases public on Exchange is safe and can come
later; deduplicating across customers is not done.

### 4. The catalog is the unit of sharing

- **A tenant opts in** (ADR 0050 §10), by its administrator with `INTEGRATIONS_MANAGE`, which records
  consent: who, when.
- **At the opt-in it chooses a default**, share or not, explicitly: there is no silent default.
- **Each catalog** is `INHERIT` (follow the tenant's default), `SHARE` or `NEVER`. This works for
  received catalogs too: whether a tenant shares a catalog is the tenant's choice, not its author's.
- **Tightening is easy, loosening is not.** Setting a catalog to `NEVER` needs only `CATALOG_MANAGE`,
  the permission that already governs the catalog; neither that nor opting the tenant out is ever
  refused for what it stops sharing, and both take effect at the next round. Setting `SHARE`, or a
  default of share, needs `INTEGRATIONS_MANAGE`, the permission whose holder consented.
- **Withdrawal is deletion.** A catalog no longer shared drops out of the set, and its releases are
  deleted unless another shared binding references them. A tenant that opts out sends an empty set;
  the service confirms that nothing of it is held, and the suite records that confirmation. Until then
  the tenant stays in the reconcile.
- **Dependencies go with the release** (amended 2026-11-06). Epistola cannot render a release
  without the releases it depends on, so a shared release brings every release it depends on, all
  the way down, as bindings of their own, whatever their catalogs' own policy. **"Never share"
  wins**: a release whose dependencies include a release of a catalog marked `NEVER` is not sent at
  all, and the page names the catalog that holds it back. (Originally dependencies were named only,
  which left the copy unrenderable.)
- **Epistola's own catalogs are shared like any other** (amended 2026-11-06): the system catalog and
  catalogs bundled with the suite take a policy and go when shared or depended on. The service holds
  release content once for all installations, keyed by content hash, and asks only for what it
  lacks, so content Epistola already has is not uploaded again.
- **Releases only.** Drafts are not sent. Sharing a catalog's drafts can be added later as an option.

### 5. Amendment to ADR 0034 §7: custody is not publication

ADR 0034 §7 says a `local` name is valid only within the organisation and never published outside it.
A mirror under a support contract does not publish: it holds a copy for the party the organisation
contracted, nobody resolves names against it, and nothing is distributed from it. `local` releases may
therefore go to the mirror, under the tenant's opt-in. §7 stands for every place that names or
distributes: Exchange, registries, files given to others. Recorded as an amendment in ADR 0034.

### 6. Not a general mirror or backup

The mirror holds what a tenant chose to share, for as long as it shares it. It is not a backup, does
not hold drafts, deletes on withdrawal, and gives nothing back to the installation. An installation
that needs its catalogs restored uses its own backups.

### 7. Results come back (deferred)

The point of the copy is a result: per release, per suite version, whether it renders the same.
Results flowing back to the suite, shown per release and as a badge on the catalog page, are the
next step; the runner that produces them is not part of the pilot.

## Consequences

- **Tenant content leaves the installation**: whole release archives (templates, themes, stencils,
  fonts, images, code lists). Hence the tenant opt-in with consent, the per-catalog choice, and a row
  in [`docs/outbound-traffic.md`](../outbound-traffic.md).
- **Bounded by the protocol**: at most 10 000 bindings per reconcile and 20 MiB per archive. A larger
  tenant or release cannot be mirrored in v1.
- **One hash, several names.** The hash leaves out a release's name and presentation, so two bindings
  can share one archive whose manifest names only one of them. The service keeps bindings apart from
  content and never takes a name from an archive.
- **No events.** A new release, a deletion or a rename reaches the mirror at the next reconcile, not
  at once. A rename is a removed and an added binding with the same hash, so no archive moves.
- **Releases are rebuilt on demand.** The suite keeps no outbox of archives: a missing hash is exported
  again from the release's stored content when it is asked for.
- **Outbound HTTP outside transactions**, as everywhere: reading the bindings and recording the round
  are short transactions; the calls to the service run between them.
- **ADR 0034 gains an amendment** to §7 (§5 above).

### Deferred

- The health-check runner, results per release per suite version, and the badge on the catalog page.
- Taking releases installed from Exchange from Exchange by hash, instead of uploading them.
- Sharing drafts.

## References

- [`docs/support-protocol.md`](../support-protocol.md), "Catalog mirror".
- [ADR 0034](0034-content-addressed-releases-and-named-pointers.md): releases, bindings, `local`.
- [ADR 0038](0038-catalogs-in-2-0.md): one table for every catalog version; every release has a hash.
- [`git push --mirror`](https://git-scm.com/docs/git-push#Documentation/git-push.txt---mirror): the
  shape of the reconcile.
