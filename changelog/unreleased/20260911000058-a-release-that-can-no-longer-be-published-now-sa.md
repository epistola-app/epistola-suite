---
type: fix
scopes: [exchange]
audience: user
title: A release that can no longer be published now says so.
---

Publishing an existing release rebuilds it from the working copy and refuses unless that still matches, because a release is published exactly as it was cut — so a release left unpublished while the catalog moved on cannot be sent at all. The action used to disappear with no explanation; the catalog page now names the version, says why, and points at releasing the current state instead.
