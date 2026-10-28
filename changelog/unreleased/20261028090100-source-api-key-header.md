---
type: fix
scopes: [catalog, code-lists]
audience: user
title: A catalog or code-list source with API-key authentication gets the key as `Authorization: ApiKey`.
---

Since 2.0 an Epistola server reads an API key only from `Authorization: ApiKey <key>`, but a
subscribed catalog or a code list with API-key authentication still sent it as `X-API-Key`, so a
2.0 suite could not read a catalog from another 2.0 suite behind an API key. The key is now sent
both ways, so sources that read `X-API-Key` keep working.
