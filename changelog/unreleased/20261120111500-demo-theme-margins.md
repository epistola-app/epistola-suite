---
type: fix
scopes: [demo]
title: The demo catalog's Corporate Theme has page margins of 25 mm instead of 72 mm.
---

Theme page margins are millimetres; the demo theme's `72` was meant as points, so every demo
template taking the theme's margins rendered in a 66 mm column. Demo catalog 6.10.0 sets 25 mm on
each side. Templates with page settings of their own (Invoice, Simple Letter) are unchanged.
