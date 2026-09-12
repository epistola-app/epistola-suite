---
type: feat
scopes: [exchange]
audience: user
title: Added opt-in Exchange discovery and tenant enrollment storage.
---

The deployment gate defaults off and discovers the official Exchange through epistola.app. Application secrets, access and refresh tokens, and the pending PKCE verifier are encrypted at rest, one logical Exchange connection is retained per Suite tenant across reauthorization, and the UI displays Exchange's stable `tc_`-prefixed connection reference instead of requiring a raw UUID. Connection/runtime publication state is deliberately excluded from portable tenant backups.
