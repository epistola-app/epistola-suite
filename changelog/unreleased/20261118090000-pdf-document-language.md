---
type: fix
scopes: [generation]
title: A generated PDF declares the language it was rendered in.
---

A PDF's document language (`/Lang`, which screen readers use to pick a voice) was always `nl-NL`, whatever the document's language. It is now the locale the document was rendered with: the variant's `system.locale`, else the tenant default, else the installation default. An installation and tenant with no locale set now declare `en-US`, the installation default.
