---
type: fix
scopes: [exchange]
audience: user
title: Failure reasons are recorded as data, so improving the wording improves what is already there.
---

`last_error` held a sentence composed inside a background worker, which is how the transport's own words — `401 Unauthorized: {"error":"invalid_client"}` — became the whole explanation, and why fixing the wording only ever helped failures that had not happened yet. Connections and publications now store an `error_code` and the far side's `error_detail` separately; the page turns the code into a sentence and shows the detail beneath it, labelled. A recorded reason also beats the connection status now, because a code knows more than a status does: "Exchange no longer recognises this installation's application" tells you reconnecting is not enough, where the status can only say credentials were refused. Implements ADR 0017.
