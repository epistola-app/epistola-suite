---
type: fix
scopes: [security, auth]
breaking: true
title: The localauth profile refuses its shipped passwords outside local development.
---

`localauth` falls back to the passwords `admin` and `user` when none is configured, and its admin
holds every platform role. Unless the `local` profile is active too, the application now refuses to
start while a local user still has one of them. Set `LOCAL_AUTH_ADMIN_PASSWORD` and
`LOCAL_AUTH_USER_PASSWORD` (Helm: `config.env`) before upgrading an installation that signs in with
`localauth`.
