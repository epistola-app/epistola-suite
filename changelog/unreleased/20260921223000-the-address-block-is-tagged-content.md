---
type: fix
scopes: [generation]
audience: user
issues: [752]
title: The address block is tagged content, read first by screen readers.
---

The address block was drawn at absolute coordinates outside the normal layout flow, on its own
content stream, with no artifact marking and no structure tree entry: a PDF/UA-1 violation on every
document with an address block. Unlike the page header, footer and preview watermark, the recipient
address is meaningful content, so it is now tagged into the structure tree rather than marked as an
artifact, which would have hidden it from screen readers. It is tagged first in reading order, before
the text beside the address window and the body, also when the letter runs over several pages.
