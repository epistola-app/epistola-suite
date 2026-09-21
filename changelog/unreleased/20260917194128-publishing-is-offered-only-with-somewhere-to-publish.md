---
type: fix
scopes: [exchange, catalog]
audience: user
title: Publishing to Exchange is offered only when the catalog has somewhere to publish.
---

The catalog page offered **Publish current release** and a namespace picker even when the tenant was
not connected to Exchange, or its organization had granted it no namespace. The picker then had
nothing in it, and pressing the button produced only the browser's own "please select an item in the
list". The controls now appear once a release could actually reach Exchange, and the page says which
of the two is missing and where it is fixed — the same three explanations the release dialog already
gave. Setting the publication policy is still possible beforehand, since a policy is worth choosing
ahead of a destination.
