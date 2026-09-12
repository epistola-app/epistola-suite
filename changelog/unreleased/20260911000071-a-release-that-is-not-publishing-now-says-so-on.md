---
type: fix
scopes: [exchange]
audience: user
title: A release that is not publishing now says so on the catalog page.
---

A stalled publication looked exactly like a healthy one where an author actually stands — an in-progress badge and nothing else — for as long as it took to give up on it. The catalog page now warns when a release has been in flight for over an hour, the same threshold the Exchange settings page already used, and points at the recorded reason and the submission on Exchange. Measured from when the release was queued rather than from its last change, because a submission Exchange is holding is re-polled every thirty seconds and would otherwise look busy indefinitely.
