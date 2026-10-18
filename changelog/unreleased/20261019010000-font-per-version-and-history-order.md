---
type: fix
scopes: [fonts, environments]
title: A preview or document embeds its own version's fonts, and an environment's history lists changes in order.
---

Previewing long-term work right after the next version (or the other way round) could embed the other version's font file for up to ten minutes when the two use different files for the same font. Each render now uses the faces of the version it renders.

When one action changed an environment twice, such as deploying a version in place of another, the history could list the two entries the wrong way round. It now lists them in the order they happened.
