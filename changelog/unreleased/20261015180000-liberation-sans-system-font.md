---
type: feat
scopes: [fonts]
audience: user
title: Liberation Sans is available as a system font.
---

Liberation Sans is metric-compatible with Helvetica and Arial, so a letter designed in either keeps
the same widths and line breaks in Epistola. It joins the eight bundled families in every tenant's
`system` catalog (slug `liberation-sans`), in the font picker, over MCP (`list_fonts`) and over REST,
with regular, bold, italic and bold italic faces. It is the font the renderer already falls back to,
so no new font files ship.
