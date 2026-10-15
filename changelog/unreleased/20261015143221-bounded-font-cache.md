---
type: fix
scopes: [generation, fonts]
audience: dev
title: Parsed fonts are cached within configurable bounds instead of kept until restart.
---

Rendering and upload validation parsed fonts through iText's own font cache, which never evicts: every
font face a node ever rendered or validated stayed in its heap until restart, and a parsed face is large
(an Inter face holds about 25 MB, all bundled faces together about 280 MB). Renders now share a
cache bounded by `epistola.generation.font-cache.max-size` (default `256MB`,
measured as what parsing a face allocated) and `epistola.generation.font-cache.max-faces` (default
`64`; `0` turns it off). Upload validation no longer keeps the font. No action is needed on upgrade;
raise the limits on a node that renders many distinct fonts. The Helm chart sets them through
`generation.fontCache.maxSize` / `maxFaces`, and `epistola.generation.font_cache.*` meters report
occupancy, hit rate and evictions per limit to tune them by. See [docs/fonts.md](docs/fonts.md) and
[docs/metrics.md](docs/metrics.md#font-cache).
