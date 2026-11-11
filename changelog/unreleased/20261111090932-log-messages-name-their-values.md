---
type: refactor
scopes: [logs]
title: Log messages name their values.
---

Every value in a log message is now written as a named pair after a short sentence, for example
`Catalog upgrade failed catalog=acme tenant=t-42 reason="Connection refused"`. A line can be
searched by what a value is (`catalog=acme`), and free text is quoted. The wording of most messages
changed with it, so an alert or saved search that matches the old text of a log line needs updating.
