---
type: feat
scopes: [templates, attributes]
audience: user
title: Editing a variant or an attribute no longer overwrites someone else's change.
---

The variant and attribute edit dialogs send back the revision they were opened at. When someone else saved in between, the save is refused with a message: the variant dialog keeps what you typed and asks you to reopen it, the attribute dialog shows the other person's version to make your change on. Settings that save one field as you change it (a template's name, theme, PDF/A) keep saving that field only.
