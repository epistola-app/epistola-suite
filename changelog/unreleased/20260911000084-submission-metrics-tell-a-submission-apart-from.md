---
type: fix
scopes: [exchange]
audience: dev
title: Submission metrics tell a submission apart from a poll.
---

One submission is followed by however many polls its decision takes, so the `submitted` outcome counted mostly polls and "how many releases did we send" had no answer. A `call` tag now separates them.
