---
type: fix
scopes: [generation, assets, catalog]
title: SVG images render from their own content only, and unsafe SVGs are refused on upload.
---

Rendering no longer fetches URLs or reads files an SVG references. Uploads and imports refuse SVGs with scripts, event handlers, external links or a DOCTYPE. SVGs already stored keep rendering, without their external references.
