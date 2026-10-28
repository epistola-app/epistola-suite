---
type: feat
scopes: [catalog]
audience: user
title: Every release says how it got here, and a release is a release however it arrived.
---

A release records how it came to be in the tenant: released here, uploaded, imported through the
API, installed from a registry or Epistola Exchange, received from the catalog's source, supplied
with Epistola, carried over from 1.x or restored from a snapshot, with where it came from, who did
it and when. The release page says so ("Installed from … by …"), and MCP's `list_catalog_releases`
lists it (`releasedVia`, `releasedFrom`). Nothing depends on it.

A catalog's version is its highest release, released here or received. A source offers an upgrade
only when its release is newer than that, so releasing a catalog here above its source's keeps it
up to date, and a release cut here is never reported withdrawn by the Exchange.
