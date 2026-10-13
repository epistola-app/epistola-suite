---
type: fix
scopes: [generation]
issues: [1065]
title: A stopping instance finishes its renders instead of abandoning them.
---

During a rolling update or scale-in an instance now stops claiming work, lets running renders finish for up to `epistola.generation.polling.shutdown-drain-timeout` (default 20 seconds), and returns the rest to the queue.
