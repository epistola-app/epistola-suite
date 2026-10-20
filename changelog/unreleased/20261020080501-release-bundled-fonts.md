---
type: fix
scopes: [generation, catalog]
title: Documents rendered from a published version use the system catalog's fonts again, instead of Helvetica.
---

The published version of the system catalog kept its fonts without their font files, so every document rendered from a published version that uses a bundled font (Inter, Roboto, Lato and the others) came out in Helvetica, with a warning in the log. This hit every tenant upgraded from 1.x and every new 2.0 installation alike. The system catalog's published version now keeps its bundled font files, so those documents embed the font they name.
