---
type: fix
scopes: [design-system, ui]
audience: user
issues: [835]
title: Alerts whose message contains inline code or a link read as one sentence again.
---

An alert that mentioned a setting, an identifier or a link inline — the support page's "support tier
is not enabled" notice, the catalog icon and catalog-ID clash warnings, the API-key "copy this key
now" prompt — broke its sentence into separate blocks, leaving orphaned fragments either side of a
code chip that stretched two lines tall and pushed the sentence's reading order out of sequence.
Alert messages now lay out as ordinary flowing text, so inline code, links and emphasis sit in the
line they belong to.
