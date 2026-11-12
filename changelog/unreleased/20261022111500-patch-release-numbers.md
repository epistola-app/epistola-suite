---
type: feat
scopes: [catalog]
audience: user
title: Patch releases of another publisher's catalog are received and ordered as SemVer says.
---

A fix to a catalog by someone who does not hold its namespace can be numbered `1.3.1-<namespace>.<N>`, such as `1.3.1-gemeente-x.1`, with a namespace they hold. Epistola receives such a release and orders it above the release it fixes and below the publisher's next one. A version range picks one only when it names a patch release of the same version. Publishing one here is not offered yet.
