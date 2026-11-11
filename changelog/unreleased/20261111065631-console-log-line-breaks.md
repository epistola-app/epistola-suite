---
type: fix
scopes: [logs, security]
title: A logged value can no longer forge a line in the human-readable console log.
---

A value carrying a line break, such as a name someone typed, started a new line in the console
output, which could pass for a separate log entry. Line breaks in a message are now written as `\n`,
so each event stays on one line. Stack traces are unchanged, and structured (JSON) output already
escaped them.
