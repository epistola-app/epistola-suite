---
type: feat
scopes: [catalog]
audience: user
breaking: true
title: A catalog's working copy is its draft version, and releasing it opens the next.
---

An authored catalog now consists of versions: its released ones and one draft, the working copy, with a version number of its own. A new catalog starts at draft 1.0.0, and releasing a draft opens the next patch as the new draft; you can renumber the draft, say to 2.0.0, before releasing it. On upgrade each catalog's draft is numbered after its latest release.
