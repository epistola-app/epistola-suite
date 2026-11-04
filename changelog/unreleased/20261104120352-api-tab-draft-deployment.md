---
type: fix
scopes: [ui, environments]
audience: user
title: A template's API tab no longer says an application cannot get it while an environment follows its draft.
---

The API tab said nothing in the catalog was published, so an application could not get the template,
even when an environment followed the catalog's draft and served it. It now says where the template
is live, marks an environment that follows a draft, and notes that a saved change reaches
applications through it at once. With nothing released or deployed, it offers both ways forward:
publish a release, or deploy the draft to a test environment.
