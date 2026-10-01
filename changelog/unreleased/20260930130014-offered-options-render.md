---
type: fix
scopes: [generation, editor]
audience: user
issues: [1027]
title: Letter spacing, a page background colour and "hide on first page" for page headers now render.
---

Three options the editor and the component and style registries offered had no effect on the PDF: the `letterSpacing` style, the page `backgroundColor`, and `hideOnFirstPage` on a page header. They now render — the background as an artifact beneath all page content, and a header hidden on page 1 keeping its space there, as a footer does. Template versions published from this release get them; versions published earlier keep rendering as they did.

The page format picker no longer offers `Custom`, which has no size fields and renders as A4. A template or theme that already uses it keeps it, shown as "Custom (renders as A4)".
