---
type: feat
scopes: [generation, editor]
audience: user
issues: [1307]
title: A QR code takes the author's alt text, or is marked decorative.
---

A QR code has Alt Text and Decorative in the inspector, as an image does. Alt text is what a screen
reader announces instead of the code, such as "Scan to pay your invoice"; left empty, a web link is
still read out and anything else is announced as "QR code". A decorative QR code, one whose
information is printed next to it, is skipped by screen readers (tagged as an artifact). Both keep
the document PDF/UA-1 conformant.
