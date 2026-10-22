---
type: feat
scopes: [catalog]
audience: user
title: Patch releases of another publisher's catalog are received and ordered as SemVer says.
---

A release may be numbered `MAJOR.MINOR.PATCH-<namespace>.<N>` (`1.3.1-gemeente-x.1`): a fix to a catalog by someone who does not hold its namespace, labelled with a namespace they hold, up to 128 characters. Epistola receives such a release by import, from the Exchange or a registry, and orders it above the release it fixes and below the publisher's next one everywhere releases are ordered: the catalog's version, release lists, what a version range picks (`^1.3.0` takes it, `1.3.1` does not) and what a draft is behind. Releasing one here is not offered yet; releasing still takes `MAJOR.MINOR.PATCH`.
