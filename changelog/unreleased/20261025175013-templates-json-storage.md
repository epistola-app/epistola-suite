---
type: feat
scopes: [templates, catalog, storage]
title: Templates are stored as JSON, each variant's model a content of its own, and a draft stores what its release does.
---

A template is now stored the way a release keeps it: its wire form, with each variant's model kept once by its content hash, so a draft and the release cut from it name the same content. A variant's description, which a release used to lose, is kept with the template and travels into its releases. A data example is addressed by its key, the one a release gives it, rather than by a separate id; the data contract editor takes the keys the server stores. A template's variants share its revision. The upgrade converts every draft template in the migration step and the template tables are gone; the bundled demo catalog's stencil instances are stored in the canonical form (release 6.4.2).
