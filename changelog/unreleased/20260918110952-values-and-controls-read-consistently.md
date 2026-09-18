---
type: fix
scopes: [ui, catalog, exchange]
audience: user
title: Values and controls read consistently across the app.
---

Catalog details showed similar values in different shapes: keywords and discovery attributes were
two different badge colours although both are simply labels you choose, and the source URL changed
appearance depending on whether it happened to link to Exchange. They now follow one rule — machine
values as code, label sets as outlined badges, everything else as plain text — which is written down
in the brand guide along with every badge and button variant and when to reach for each.

In the release dialog, the **Patch**, **Minor** and **Major** buttons never showed which one was
chosen, not even the patch default the version field starts on. The selected one is now marked, it
follows you as you choose, and it clears if you type a version by hand. Screen readers announce it.

On the Exchange settings page, **Reauthorize** looked like text rather than a button, which is
awkward for the action that repairs a broken connection, and **Save namespace** sat at the bottom of
the connection block as though it saved the connection. It now has a block of its own.

Status badges on the support, backup, feedback and upgrade pages were rendering as unstyled text
because they used class names that no stylesheet defined. They are styled again.
