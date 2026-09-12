---
type: fix
scopes: [exchange]
audience: user
title: A discovered OAuth endpoint must be on the issuer's own origin.
---

The issuer is what an operator chose to trust; the endpoints came from whatever document answered at that address, and the token endpoint is where the client secret and refresh token are sent. A poisoned discovery response could name any host it liked and be handed the credentials.
