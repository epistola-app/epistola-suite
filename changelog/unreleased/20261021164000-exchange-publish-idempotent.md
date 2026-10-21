---
type: fix
scopes: [exchange]
audience: user
title: Publishing a release to Epistola Exchange again is answered, not refused.
---

Publishing a release that is already queued for Epistola Exchange refused with "already has an
Exchange publication attempt". It now answers that the release is queued and queues nothing more,
and a release the Exchange accepted answers that it is published. A release the Exchange rejected is
still refused, since that number cannot be published again.
