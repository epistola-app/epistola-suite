---
type: fix
scopes: [catalog]
audience: user
title: Exporting a subscribed catalog names the version it has installed.
---

A subscribed catalog installed at 1.3.0 exported as `catalog-0.0.0-dev.zip`, with a warning that it
had never been released. It had — by whoever published it. The export now names the release the catalog has installed.
