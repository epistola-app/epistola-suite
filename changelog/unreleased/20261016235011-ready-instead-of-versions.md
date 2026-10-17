---
type: feat
scopes: [templates, catalog, generation]
audience: user
breaking: true
title: Templates have no versions any more; you mark content ready and release the catalog.
---

A template variant has one model and a template one data contract, which you edit in place: the catalog release is the only version. Instead of publishing a template version you mark a variant, a template, a stencil or the whole catalog ready, which records that its current content is reviewed; any edit makes the mark stale. A release is refused while something changed since the last release is not ready. On upgrade each variant keeps what you were working on (its draft, else its latest published version), and what was published before counts as ready, so unfinished drafts are what you still have to mark. Generation requests queued before the upgrade against a template version have to be resubmitted.
