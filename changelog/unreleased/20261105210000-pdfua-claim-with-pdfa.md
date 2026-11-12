---
type: fix
scopes: [generation, editor]
audience: user
issues: [752, 1307, 1308]
title: Generated PDFs are more accessible, and only PDF/A documents claim PDF/UA-1.
---

The address block is tagged content, read first by screen readers. A QR code takes alt text or can be marked decorative; without alt text a web link is read out and anything else is announced as "QR code". A template with PDF/A turned off does not embed its fonts, so its documents no longer claim PDF/UA-1, though they are tagged as before; PDF/A documents, the default, are validated against PDF/UA-1 and PDF/A-2b in the build. A PDF's metadata names the iText version that made it.
