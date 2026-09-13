---
type: fix
scopes: [changelog]
audience: user
title: Breaking changes are filed under their own type again, and say so.
---

The dialog's parser did not accept the `!` that marks a breaking change, so every such entry — "PostgreSQL 18 is now the minimum" among them — landed in the untyped Chores bucket with its raw `feat(db)!:` prefix showing in the text. It now reads the marker, files the entry under its real type and scope, and leads the chip row with a Breaking badge. Two entries written as `feat!(exchange):` are corrected to the documented `feat(exchange)!:`, and a new test checks every `[Unreleased]` entry against the documented format, which is what let these through.
