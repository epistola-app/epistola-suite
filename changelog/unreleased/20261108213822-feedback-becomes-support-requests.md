---
type: feat
scopes: [support]
breaking: true
title: Feedback becomes support requests, with a support desk in the installation.
---

What users report from inside the suite is a **support request** under Support → Support requests, kept in the installation as a conversation. Platform → **Support requests** is the installation's own desk: one inbox for every tenant, internal notes that never leave the installation, and passing a request on to Epistola support or a partner when the tenant shares requests with it. A new platform role, **support desk** (`SUPPORT_DESK`), works it. A request can carry the server log of the submitter's last requests, and names its submitter outside the installation by a person reference, never by name. Feedback was alpha: existing feedback is not moved, the feature toggle is now `support-requests`, the sync properties `epistola.support.requests.sync.*`, and the permissions `SUPPORT_REQUEST_SUBMIT` and `SUPPORT_REQUEST_HANDLE`. See `docs/support-requests.md`.
