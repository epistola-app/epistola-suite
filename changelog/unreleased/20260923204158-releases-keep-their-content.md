---
type: feat
scopes: [catalog, exchange, storage]
audience: user
issues: [988, 990]
title: A release keeps its content, says how it got here, and can be exported or deleted.
---

Publishing stores an immutable, deduplicated copy of everything the release holds, so editing or deleting something later never changes an earlier release. Every release has its own page: what it held, who released it or where it came from (released here, imported, installed from Exchange or a registry, carried over from 1.x), and its content fingerprint, with the other releases that have the same content. You can export any release as it was published, or **with what it uses**: one file with the releases of the catalogs it depends on, for an installation without Exchange. **Delete** removes a release with its content, unless an environment or another catalog's release uses it.
