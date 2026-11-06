---
type: fix
scopes: [api, generation]
audience: user
title: The collect feed no longer loses a result that commits after a later one.
---

A result's sequence is drawn when it is stored, but results commit in the order their transactions
finish. A consumer that was given sequence n + 1 while n had not committed yet acknowledged it, and
n was never delivered: under load, about one result in four thousand. A poll now delivers only up
to the highest sequence handed out when it started, after waiting for the results still being
stored at that moment — normally a few milliseconds. If they take longer than two seconds, the
poll returns an empty page with `hasMore: true`.
