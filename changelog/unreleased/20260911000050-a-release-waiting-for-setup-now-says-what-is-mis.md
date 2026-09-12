---
type: fix
scopes: [exchange]
audience: user
title: A release waiting for setup now says what is missing.
---

A connection granting more than one namespace leaves the default unset, because Suite will not guess a choice that becomes permanent — but nothing said so, and every release from a catalog without its own preference waited silently behind a connection that looked healthy. The Exchange page now asks for a default while releases are waiting on it, and each waiting publication records why it cannot proceed, naming the namespaces available.
