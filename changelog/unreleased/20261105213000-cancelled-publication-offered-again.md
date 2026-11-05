---
type: fix
scopes: [catalog]
audience: user
issues: [1008]
title: A release whose publication was cancelled or failed can be sent to Epistola Exchange again.
---

A release page said "On Epistola Exchange" for any release that had ever been queued, and withheld
the "Send to Epistola Exchange" action, even when that publication was cancelled or had failed.
That contradicted the cancel confirmation, which promises the release can be queued again. The page
now shows where the release stands on Exchange (for example "Epistola Exchange: Cancelled") and
offers to send a cancelled or failed one again. A rejected release is still not offered, since
Exchange does not take that version number again.
