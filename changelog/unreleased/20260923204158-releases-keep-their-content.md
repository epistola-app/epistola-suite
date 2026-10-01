---
type: feat
scopes: [catalog, exchange]
audience: user
issues: [988, 990]
title: A catalog release keeps the content it was cut with, and can be read back.
---

A release recorded a fingerprint and a promise. The content behind it lived only in the working copy,
so renaming a theme or deleting an image changed what an earlier release would give you — and
exporting one always handed over the working copy anyway, labelled `-dev` once it had drifted.
Publishing had the same root: the archive was rebuilt from the working copy, so any edit made after
releasing blocked it, and only the release you were currently on could be sent at all.

Releasing now stores an immutable, deduplicated copy of every resource it contained. What that makes
possible:

- **Export a release as it was released** — its resources as they were, carrying the version,
  timestamp and fingerprint it was cut with, whatever has been edited since.
- **See the releases behind the current one.** The catalog page lists them newest first, with when
  each was cut, what it contained and its notes.
- **Open a release** and see the resources it held and the details it carried at the time, so one
  made under an old name still says so.
- **Publish any release to Exchange**, not only the one the catalog is on, and without first
  releasing work that is not ready — a catalog whose author has moved on can still send the version
  people are asking for.

Releases cut before this cannot be rebuilt: there is nothing to rebuild them from. They say so
wherever they appear, and are not offered for export, rather than quietly handing over the working
copy in their place.

Two consequences worth knowing. Storage grows with genuine change rather than with releases —
re-releasing unchanged content stores nothing. And the bytes a release needs are no longer reclaimed
when the image or font they belong to is deleted from the working copy; a tenant backup carries them
too.
