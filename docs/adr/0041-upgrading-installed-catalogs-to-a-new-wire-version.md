# ADR 0041: Upgrading installed catalogs to a new wire version

- **Status:** Proposed (an investigation: the options, and a recommendation)
- **Date:** 2026-10-28
- **Deciders:** Epistola team
- **Tags:** catalog, storage, wire-format, fingerprints, exchange, migrations
- **Amends:** [ADR 0040](0040-storage-and-contracts.md) §2 (keeping the published form)
- **Related:** [ADR 0038](0038-catalogs-in-2-0.md), [ADR 0035](0035-data-migrations-and-skipping-upgrades.md);
  contract ADR 0003 (fingerprints), contract ADR 0006 (the v7 → v8 migration)

## Context

ADR 0040 keeps storage in the contract's wire form, so every installation stores content in one
wire version. When the contract moves to the next one (v9), everything an installation holds is in
the old one: its drafts, its own releases, and the releases it received. So is everything another
installation, a registry or the Exchange holds. The question is how all of that moves forward, and
what happens to the hashes when it does, without keeping old wire formats in the database for
long.

### What a hash answers, today

An installation records and compares several hashes. They are not the same, and they react to a
wire version differently:

| Hash                                                              | Where                                                | Answers                                                                   | Depends on the encoding?                                                             |
| ----------------------------------------------------------------- | ---------------------------------------------------- | ------------------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| **Storage hash** (SHA-256 of the stored JSON)                     | `resource_contents.content_hash`                     | where a content is stored; that a draft and its release share a row       | **yes**: any change of shape changes it                                              |
| **Release identity** (V6, `v6:<hex>`)                             | `catalog_versions.content_hash`                      | "is this the same release?", wherever it comes from (contract ADR 0003)   | **no**: V6 does not cover `schemaVersion`, and its canonical form fixes the encoding |
| **Release fingerprint** (`release.fingerprint`)                   | `catalog_versions.fingerprint`, the manifest         | verifying an archive against what it was published as; the upstream check | names its algorithm (`v5:`, `v6:`); from V6 on, no                                   |
| **Per-resource fingerprints** (`completePerResourceFingerprints`) | `release_resources.fingerprint`, `base_fingerprints` | what changed, what is behind, what merges                                 | computed by the contract per algorithm                                               |
| **Dependency pins** (`releaseFingerprint`)                        | a draft's declarations, a release's pins             | that a dependency installed is the release it was pinned to               | names its algorithm                                                                  |

Two facts from the contract settle most of the problem:

1. **Release identity is independent of the wire version** (contract ADR 0003): "a wire version
   that only re-encodes the same content keeps every release's V6 value".
2. **Every fingerprint algorithm stays computable from migrated content.** V1 to V4 canonicalize
   the members wire v8 renamed under their old names, "so they reproduce exactly the fingerprints
   producers computed before the rename, whichever wire version the archive is read at"
   (`CatalogCanonicalizer`). A fingerprint names its algorithm, and a verifier computes exactly
   that algorithm.

And one from the suite: the 1.x → 2.0 upgrade already migrated every stored content in place, by
the same rules as the archive migrator (contract ADR 0006: "an archive and a database migrate to
the same").

So only the storage hash changes when content is re-encoded, and nothing outside the installation
ever sees it.

### What can still go wrong

- **A wire migration that is not only a re-encoding.** A new required member with a derived value
  changes the canonical form, so V6 moves, and with it the release's identity. The release is then a
  different release by the contract's own definition, though nobody changed it.
- **A new identity algorithm with the new wire version** (as V6 came with v8). Each release records
  its fingerprint in the old algorithm; a registry may list the new one.
- **Installations at different wire versions.** An installation still on v8 cannot read a v9
  archive. One on v9 reads v8 archives (the migrator upgrades them on import).
- **Byte-identical re-export.** A migrated release exports in the current wire version: same
  identity, different bytes from the archive it arrived as.

## Options

### For stored content

**A. Migrate in place, once, at upgrade.** A code migration (ADR 0035) re-encodes every stored
content, drafts and releases alike, by the contract migrator's rules. Rows point at the new
contents; the old ones are collected. Before it commits, it recomputes each release's identity and
fails on a difference.

- No old format stays in the database.
- Storage hashes change. Only storage uses them: a draft and its release still share a row, because
  both are migrated alike.
- A received release no longer exports byte for byte as it arrived. It still verifies by its
  identity and its recorded fingerprint, which name their algorithm.

**B. Keep the published form and add a current copy** (ADR 0040 §2 as written). Exact re-export, at
the price of every content twice, the old format kept for good, and two readers of storage.

**C. Keep the old form for a grace period.** A, but the old contents stay until a later release's
cleanup migration drops them: a way back if a migration turns out wrong.

**D. Upgrade on read.** Rejected in ADR 0040: a migration in front of every render.

### For releases elsewhere (the Exchange, registries, other installations)

**E. Serve what was published; the importer migrates.** An archive stays in the wire version it was
published in. An installation that reads it upgrades it on import, as today. One that is older than
the archive cannot read it, and is told so.

**F. The Exchange converts.** It keeps the published archive and serves it in the wire version an
installation asks for: up by the contract migrator (deterministic, so a converted archive can be
cached), down only where that loses nothing, else refused with "upgrade your installation". One
release, several encodings, one identity.

**G. The publisher releases again in the new format.** A new number for the same content. By
contract ADR 0003 it is the same release (same V6), bound to a second number, which makes the
version history say something happened when nothing did.

### For a new identity algorithm

**H. Record a fingerprint per algorithm.** When a wire version brings a new identity algorithm, the
migration computes it for every release from its migrated content and records it beside the one it
was published with. Comparison picks the algorithm both sides have. This works because of fact 2.

## Recommendation

1. **Stored content: A, with a short C.** Migrate in place at upgrade; keep the replaced contents
   only until the next minor release's cleanup, as a way back. That meets the wish not to keep old
   formats for long, and costs nothing permanent.
2. **A wire migration must not move an identity.** The migration recomputes every release's V6 and
   fails the upgrade on a difference. A wire change that would move one is not a wire migration: it
   needs a new identity algorithm (H), decided in the contract, never discovered in an installation.
3. **Received releases are verified by identity, never by bytes.** Re-exporting a migrated release
   gives a current-format archive with the same identity and recorded fingerprint. The one digest of
   archive bytes the suite keeps, `catalog_upstream_checks.received_archive_sha256`, is of what a
   source served, for a conditional refetch; it is never compared with what the suite exports, so a
   migration does not touch it. This makes "identity, not bytes" a rule.
4. **Elsewhere: E now, F when there are two wire versions in the field.** Importers already migrate
   up. When an older installation must read a newer release, the Exchange converts down where that
   is lossless. Publishers do not need to release again (not G).
5. **A new identity algorithm: H.** Releases carry their fingerprint per algorithm; the upgrade adds
   the new one; comparison uses one both sides have.
6. **Stored content records its wire version** (ADR 0040), so the upgrade knows what to migrate and
   a half-finished one can resume.

This amends ADR 0040 §2: the published form is not kept, because identity and fingerprints, not
bytes, are what anyone verifies.

## Consequences

- One wire version in the database at any time, plus the replaced contents for one minor release.
- A wire version costs: the contract's archive migration (exists), the same migration of stored
  content (ADR 0035), and an identity check over every release. The 1.x → 2.0 upgrade did all three.
- An exported archive is always in the current wire version. A recipient on an older suite reads
  it only through the Exchange's conversion (F), or after upgrading.
- The rule "a wire migration never moves an identity" constrains the contract. It already holds it
  for V6.

## Open questions

1. **Down-conversion.** Which wire changes can the Exchange convert down without loss, and does the
   contract publish a down-migrator, or only the Exchange?
2. **The grace period.** One minor release, or until the next major?
3. **Per-resource fingerprints.** They are computed per algorithm like the release's. Are any stored
   per-resource values in an algorithm the migrated content cannot reproduce? The contract says
   none are today; the migration should check it as it checks V6.
4. **Pins across wire versions.** A pin names its algorithm, so it verifies against a release at any
   wire version. Confirm the deploy check compares by the pin's algorithm, not the current one.

## How it would be built

1. The wire-version column (ADR 0040, step 3).
2. A stored-content migration framework: per wire version, the contract migrator's rules applied to
   each content, rows re-pointed, V6 recomputed and compared, resumable (ADR 0035).
3. The cleanup migration of the next minor release.
4. Fingerprints per algorithm on a release (H), when the contract brings a new one.
5. The Exchange's conversion (F), when two wire versions are in use.
