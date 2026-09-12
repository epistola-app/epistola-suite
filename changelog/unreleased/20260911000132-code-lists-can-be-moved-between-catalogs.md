---
type: feat
scopes: [catalogs]
audience: user
title: Code lists can be moved between catalogs.
---

The fourth relocatable type, and the cheapest: nothing in versioned content names a code list, so a move rewrites no payloads. Its entries and any attribute bound to it — including from another catalog — follow by `ON UPDATE CASCADE`, so a rename carries them too.
