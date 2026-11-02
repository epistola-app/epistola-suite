# TLA+ specifications

Models of what the suite does when commands run at the same time, checked by TLC, the TLA+ model
checker: it tries every interleaving of a small instance and reports a sequence of steps that breaks
an invariant. A model is of the design as the code implements it, not of the code itself, so what
it finds is proven against PostgreSQL by a test before it is fixed (see [`docs/testing.md`](../../docs/testing.md),
"Commands at the same time").

## `CatalogConcurrency.tla`

Catalog commands as PostgreSQL READ COMMITTED transactions: each statement reads what is committed
when it starts; inserting a row whose foreign key names a version takes a key-share lock on that
version's row; a `DELETE` takes a lock that waits for key-share holders and makes their foreign-key
check fail once it commits. A check that ran before such a wait is not run again.

| Command       | Code                                   | What it checks, then writes                                                                  |
| ------------- | -------------------------------------- | -------------------------------------------------------------------------------------------- |
| Declare       | `CatalogDependencyStore.replace`       | no cycle among what every version declares (`requireNoCycle`), then the declaration          |
| Release       | `ReleaseCatalogDraft`                  | what each declaration pins (a named draft with nothing to release: its base), then the pins  |
| DeleteRelease | `DeleteCatalogRelease`, `ReleaseInUse` | nothing pins the release, then the `DELETE` (a declaration's target is `ON DELETE SET NULL`) |

The invariants are `CatalogInvariants`' SQL rules of the same names: `NeverUseEachOther`
(`catalogs-never-use-each-other`), `ReleasePinsReleases` (`release-pins-releases`) and
`PinnedReleasePresent` (`pinned-release-present`).

The constant `Fixed` switches in the locks the fix takes, so both designs are checked:

| Config                        | Fixed   | Expected                                          |
| ----------------------------- | ------- | ------------------------------------------------- |
| `CatalogConcurrency.cfg`      | `FALSE` | violations of all three invariants, one race each |
| `CatalogConcurrencyFixed.cfg` | `TRUE`  | none                                              |

The races, each proven by `CatalogConcurrencyTest` (it failed before the fix):

1. **Two catalogs declaring each other at once** both pass the cycle check, as neither sees the
   other's uncommitted declaration. Fix: a tenant-wide advisory lock before the check
   (`requireNoCycle`), held until commit.
2. **A release deleted while a version pinning it is written** (a draft declaring it, or a release
   cut that pins a named draft's base): the in-use check passes, the `DELETE` waits for the pin to
   commit and then goes ahead, and `ON DELETE SET NULL` leaves the pin naming a release that is gone.
   Fix: the release's row locked `FOR UPDATE` before the check (`ReleaseInUse.requireUnused`), so
   the check waits for the pin and then sees it.
3. **A draft written while it is released** (found by TLC, not by reading the code): the release
   reads the draft's declarations, a declaration of it commits, and the release then freezes it,
   unpinned (`ReleasePinsReleases`). A write takes only a key-share lock on the draft's row, which
   the release's `UPDATE` does not wait for. Fix: the release locks the draft's row `FOR UPDATE`
   before it reads it (`CatalogDrafts.lockForRelease`), and the freeze check every write makes
   (`requireWritableVersion`) reads it `FOR SHARE`: a write waits for the release and then finds
   the draft released, or the release waits for the write and then reads it.

Not modelled yet: discarding a draft another catalog's draft is naming at that moment, installing a
release, and relocation. Discarding a draft has the same shape as deleting a release (a check, then
a `DELETE` whose foreign key sets the declaration's target to null), so it is the next to add.

## Running TLC

```bash
./gradlew :specs:tla:tlc
```

It runs TLC (the stable release, 1.7.4, its jar checked against a SHA-256 in `build.gradle.kts`) on
each configuration: the code before the fixes once per invariant, each of which must be violated
(TLC stops at the first violation, and which comes first varies), and the fixed code, which must
hold everywhere. A failure prints TLC's output, with the steps that break the invariant. TLC runs on
a copy of the specs in `build/tlc/`, since it writes a trace module beside the spec it checks. It is
not part of `check` yet.

The instance is two catalogs, two sessions and two commands per session; raise `MaxCmds` or add a
catalog to search further, at the cost of time.
