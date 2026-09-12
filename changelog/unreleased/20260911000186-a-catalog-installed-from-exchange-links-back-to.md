---
type: feat
scopes: [catalog, exchange]
audience: user
title: A catalog installed from Exchange links back to it.
---

A subscribed catalog is a read-only mirror, so its resources have no page of their own here and were rendered as plain text. When the catalog came from Epistola Exchange there is a page to open: the namespace and key are already recorded in the catalog's source, so each resource row now links to its own page on Exchange, and the Source line links to the catalog's. Nothing is fetched to build them and nothing is guessed — a catalog subscribed from a plain URL, imported from a ZIP, or authored locally reads exactly as before. A private catalog's page will ask the reader to sign in, which is Exchange's decision to make rather than ours to pre-empt. This is the whole of what a Suite can currently say about a resource it has not downloaded; showing a release's contents before installing needs [a resource inventory on Exchange's API](https://github.com/epistola-app/epistola-exchange/issues/8).
