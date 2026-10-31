---
type: feat
scopes: [quality]
audience: user
maturity: alpha
title: Quality checks read a template's text for language problems, on every save.
---

Built-in "clear language" rules report repeated words, sentences of more than 25 words, spacing
mistakes and formal words with a plain alternative (Dutch and English), each explaining why. A
LanguageTool server adds spelling, grammar and style: an operator sets
`epistola.quality.language-tool.url`, and a tenant administrator switches it on in the new quality
settings; nothing is sent anywhere otherwise. Checks see template text only, every expression as a placeholder, never data,
and each paragraph is checked once until it changes. The editor panel shows the flagged text and
suggestions, never applied for you, and says when a check was skipped or failed.

Templates are checked a few seconds after each save, and at once when a variant is marked ready, which
before checked nothing. Requires the `quality` feature; see `docs/quality.md`.
