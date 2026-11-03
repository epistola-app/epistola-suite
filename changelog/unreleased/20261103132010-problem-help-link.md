---
type: feat
scopes: [api]
title: An API problem links its type's page with the operation that answered, for hints that fit it.
---

Every problem a REST operation answers carries `help`, its type's page with the operation in the query, such as `https://epistola.app/errors/not-found?operation=getTemplate`, where the website gives the hints for that operation. It names the operation only, never a value from the request. A problem raised before an operation ran, such as a refused API key, has none. Switch on `type` as before: `help` is for people.
