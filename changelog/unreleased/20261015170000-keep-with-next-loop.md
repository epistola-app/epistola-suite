---
type: fix
scopes: [generation]
audience: user
title: keepWithNext on a heading before a loop keeps it with the first item, not the whole loop.
---

A heading with "keep with next" in front of a loop waited until every item of the loop fitted on the
same page, so with a few cards the heading and all of them moved to the next page and left the end of
the previous one empty. Newly published versions use rendering defaults V6, where the heading keeps
with the first block after it, as in Word. Versions published earlier keep rendering as they did.
