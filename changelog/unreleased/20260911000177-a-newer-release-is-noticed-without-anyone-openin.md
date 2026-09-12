---
type: feat
scopes: [catalog]
audience: user
title: A newer release is noticed without anyone opening the catalogs page.
---

Checking for updates used to happen only while that page rendered, one round-trip per row, so a release could sit unnoticed for as long as nobody visited. A background check now asks each subscribed catalog's source on a schedule and records the answer; the page renders from that, and the button beside each catalog forces a fresh answer for anyone unwilling to wait. Two cadences, not one: the task looks for due catalogs every minute, but any single catalog is asked every six hours, jittered so twenty installed the same afternoon do not come due together for ever. A failing source is recorded and stepped past rather than escalated, and there is deliberately no attempt limit — giving up would quietly stop telling anyone about upgrades, which is the one thing this exists to do. Applies to catalogs subscribed from a URL as much as to Exchange ones; only the asking differs.
