---
type: fix
scopes: [generation, assets]
title: A template or image can no longer exhaust a render node's memory.
---

A QR code with a very large size made the renderer allocate an image of that many pixels squared, which could stop the node rendering it; its resolution is now bounded (the PDF scales it to the size asked). An uploaded image whose header claims more than 40 million pixels is refused with its size named, and an image's size is read from its header instead of by decoding it.

A document whose render keeps stopping the node rendering it is no longer handed to the next node forever: after three such recoveries it fails, saying why (`epistola.generation.polling.max-recoveries`).
