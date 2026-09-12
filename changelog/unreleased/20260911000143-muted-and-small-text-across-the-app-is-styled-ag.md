---
type: fix
scopes: [ui, design-system]
audience: user
title: Muted and small text across the app is styled again.
---

Markup in eleven places reached for `ep-text-muted`, `ep-text-sm`, `ep-text-xs` or `text-sm`; the first is a wrong prefix for `text-muted`, and the size classes never existed at all — only the `--ep-text-*` tokens behind them. Those elements rendered at default weight and size with nothing to show they had asked for anything. The prefixes are corrected and `.text-sm` / `.text-xs` now exist.
