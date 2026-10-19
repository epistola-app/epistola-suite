---
type: feat
scopes: [catalog, exchange, storage]
audience: user
issues: [988, 990]
title: A published catalog version keeps its content, and can be read back, exported or deleted.
---

In 1.x a catalog release recorded a fingerprint, but its content lived only in the working copy, so renaming a theme or deleting an image changed what an earlier release would give you, and exporting a release handed over the working copy, labelled `-dev` once it had drifted. Publishing a version now stores an immutable, deduplicated copy of every resource it contains, so storage grows with genuine change rather than with each version. Every version has its own page with what it held and the details it carried at the time; you can export it as it was published, whatever has been edited since, and send any published version to Epistola Exchange, not only the latest. A subscribed catalog lists the versions it has installed. Releases cut under 1.x kept no content, so the upgrade removes them (see the upgrade entry). Because a published version keeps the image and font bytes it needs, deleting them from the next version no longer reclaims them. A version always holds its content: **Delete** removes the version with it, which cannot be undone and is not allowed while an environment uses the version or another catalog's version was published with it. Deleting a catalog now frees what its versions held.
