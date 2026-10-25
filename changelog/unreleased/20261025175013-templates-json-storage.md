---
type: feat
scopes: [templates, catalog, storage]
title: Templates are stored as JSON, each variant with its own content and revision, and a draft stores what its release does.
---

A template is now stored the way a release keeps it, so a draft and the release cut from it name the same content: the template's own fields in one content, and each variant (title, model, description) in one of its own. A variant's description, which a release used to lose, is kept with the template and travels into its releases. A data example is addressed by its key, the one a release gives it, rather than by a separate id; the data contract editor takes the keys the server stores. Each variant has its own revision and timestamps: saving one variant does not touch the template or its other variants, so two people editing different variants of one template no longer conflict. The upgrade converts every draft template in the migration step and the template tables are gone; the bundled demo catalog's stencil instances are stored in the canonical form (release 6.4.2).
