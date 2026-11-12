---
type: fix
scopes: [security, generation]
breaking: true
title: A template or image can no longer tie up or exhaust a render node.
---

JSONata's `$pad` now pads to at most 100 characters; a wider pad could hold a CPU core for hours. An expression that runs past its 5 seconds, recurses more than 100 levels or nests too deeply no longer renders as empty: the preview names it, and generation fails with that message. A QR code's resolution is bounded, an uploaded image whose header claims more than 40 million pixels is refused, and a document whose render keeps stopping a node fails after three recoveries (`epistola.generation.polling.max-recoveries`) instead of moving from node to node.
