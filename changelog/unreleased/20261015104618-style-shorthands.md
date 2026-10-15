---
type: fix
scopes: [generation]
audience: user
title: The padding, margin and border style shorthands now render.
---

The style registry offers `padding`, `margin` and `border`, but the PDF renderer only read the
per-side keys (`paddingTop`, `borderLeft`, …), so a template or theme preset written with the
shorthands — typically over MCP or from an imported catalog — rendered without padding or outline.
Newly published versions use rendering defaults V6, which expands the shorthands (one to four values
in CSS order; an explicit side wins). Versions published earlier keep rendering as they did. The
editor still writes per-side keys, which is also what its inspector edits.
