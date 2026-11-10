---
type: fix
scopes: [security, generation]
breaking: true
title: An expression past its limits fails the render with a message naming it, and `$pad` pads to at most 100 characters.
---

One expression could tie up a CPU core for hours: JSONata's `$pad` takes any width and builds its
result in time growing with the square of it, and runs inside a single evaluation step where the
5-second budget is never checked. `$pad` now pads to at most 100 characters. An expression that runs
past its 5 seconds, recurses more than 100 levels, nests too deeply to parse, or pads wider than 100
no longer renders as empty: the editor preview shows a message naming it, and a document's
generation fails with that message. Other expression errors still render as empty.
