---
type: feat
scopes: [catalog, storage]
audience: user
title: A release can be deleted or have its content dropped, and deleting a catalog frees what its releases held.
---

Releasing keeps an immutable copy of everything the release contained, and nothing removed it. An
image released by mistake stayed for the life of the installation: deleting it from the working copy
left every release that carried it holding it still, deliberately, so those releases stay
reproducible. There was no lever at all.

Each release in a catalog's history now offers **Forget content**. The release stays — version, date,
notes and fingerprint, the evidence of what that version was for whoever installed it — and its
content is deleted. It reads as "not kept" from then on, exactly like a release cut before Epistola
retained release content, and can no longer be exported or published as released. It cannot be
undone. Content shared with another release that still keeps its own is untouched.

A release can also be **deleted** outright, for a version that should not be in the history at all:
the row goes with its content, notes and fingerprint, and the catalog's current version follows back
to the release before it. Anything already published to Epistola Exchange stays there — that copy is
Exchange's. The only refusal is while a publication of that exact release is still being sent.

**A fix in the same place:** deleting a catalog took its releases with it but left their content
behind, reachable from nothing and impossible to reclaim — including the image and font bytes those
releases were holding back from the nightly sweep. Deleting a catalog now frees them.
