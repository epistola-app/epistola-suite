---
type: fix
scopes: [exchange, ui]
audience: user
title: The install dialog opens.
---

The version picker was swapped into its container and then sat there invisibly: `data-open-dialog` is a _click trigger_ for a button, not the hook that opens a dialog arriving by swap — that is `data-dialog-mount` on the container. Clicking Install appeared to do nothing at all. A refusal now also renders into the dialog's own error slot instead of replacing the dialog, so a rejected install no longer takes away the version the reader had just chosen. No server assertion could have caught this, since the fragment was returned correctly either way; a test now pins the mount attribute.
