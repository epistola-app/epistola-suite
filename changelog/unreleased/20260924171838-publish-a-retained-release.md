---
type: fix
scopes: [catalog, exchange]
audience: user
issues: [990]
title: Publishing a release to Exchange no longer requires an untouched working copy.
---

Publishing an existing release rebuilt its archive from the working copy, so any edit made after
releasing blocked it: sending v1.0.0 to Exchange meant first releasing work that was not ready. The
release keeps its own content now, so publishing it sends what it was, whatever has changed since.

A release cut before Epistola kept release content still has only the working copy to rebuild from,
and still says so.
