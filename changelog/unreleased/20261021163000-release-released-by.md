---
type: feat
scopes: [catalog]
audience: user
title: A release says who released it.
---

A release cut in this installation records the user who released it and this installation, and a
received release the installation that released it, which every archive of a release names in its
`release.metadata` (`app.epistola.suite.releasedByInstallation`). The release page says "Released
here by …" or "Released by installation …". Releases recorded before say neither.
