---
type: fix
scopes: [exchange]
audience: user
title: Setup failures stay on the Exchange page instead of becoming error pages.
---

The authorization callback — where a reused code, an expired state or a mismatched application is most likely — now reports on the settings page like every other setup action, and an unreachable or misbehaving Exchange is reported there too rather than as an unexpected error. A malformed callback is a bad request instead of a server error. Rejecting a publish of the current release likewise returns the catalog page with the reason.
