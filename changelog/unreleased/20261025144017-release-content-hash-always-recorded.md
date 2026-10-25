---
type: refactor
scopes: [catalog, releases]
title: Every release records its content fingerprint when it is cut or received; the background job that filled them in later is gone.
---

A release's content fingerprint is recorded when the release is cut or received, and after the upgrade to 2.0 no release is without one: 1.x kept no release content and the upgrade replaces the 1.x releases with each catalog's first 2.0 release. The background job that computed missing content fingerprints every six hours, and only warned once there was nothing left, is removed, and a release page no longer says its content fingerprint is being computed.
