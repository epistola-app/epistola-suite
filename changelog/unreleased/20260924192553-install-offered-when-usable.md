---
type: fix
scopes: [catalog]
audience: user
title: A fully installed catalog no longer offers to install itself.
---

The "Install catalog" action appeared on every subscribed catalog, including ones whose resources
were already installed, where it opened a dialog with nothing to do. It now appears only while the
source holds something this installation does not. Moving to a newer release is the separate upgrade
action, as before.
