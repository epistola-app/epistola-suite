---
type: feat
scopes: [generation, themes, editor]
title: Block spacing comes from the theme, and no longer grows with the spacing unit.
---

Rendering defaults V7 take a block's vertical rhythm from the theme. A theme can set the space above
and below every block (`defaultBlockStyles`, margins only: in the theme editor, REST and MCP); text,
containers, columns, tables, images and QR codes take it. Without it a block keeps 6pt below it,
measured in points: before, the `1.5sp` default grew with the theme's spacing unit, to 24pt at a 16pt
unit. Text keeps no space of its own, and a separator its own margins. The editor's canvas spaces
blocks the same way.

Drafts render with V7 at once. A release cut earlier renders exactly as before; a catalog moves to V7
with its next release.
