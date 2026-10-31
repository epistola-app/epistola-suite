---
type: feat
scopes: [quality]
audience: user
maturity: alpha
title: Quality checks read a template's text for language problems, on every save.
---

Built-in writing rules report repeated words, sentences of more than 25 words, spacing mistakes and
formal words with a plain alternative (Dutch and English). A LanguageTool server you run adds
spelling, grammar and style: set `epistola.quality.language-tool.url`; nothing is sent anywhere
unless you do. Checkers see template text only, with every expression as a placeholder, never data,
and each paragraph is checked once until it changes. The editor panel shows the flagged text and
suggestions, never applied for you, and says when a checker was skipped or failed.

Templates are checked a few seconds after each save, and at once when a variant is marked ready,
which before checked nothing. Requires the `quality` feature; see `docs/quality.md`.
