---
type: fix
scopes: [exchange]
audience: user
title: A rejected Exchange response now explains itself on the settings page.
---

Reaching Exchange and getting back something unusable — a discovery document version Suite does not understand, OAuth metadata advertising a different issuer than discovery, a missing field, a non-HTTPS endpoint — produced an unexpected-error page with no indication of what disagreed. These now raise a distinct failure that the Exchange page reports in full, naming both sides of the mismatch, so the most likely first-connection problem is readable rather than a stack trace in the log.
