---
type: chore
scopes: [agents]
audience: dev
title: A hook stops assistants reading the whole changelog.
---

`CHANGELOG.md` is 700 KB of mostly released history, and an assistant that opens it whole to add one entry spends a large part of its context on it — 64 such reads across the sessions studied, two of which failed outright on size. A `PreToolUse` hook now refuses a read of that file without a small line limit and says what to do instead. The script behind it takes JSON on stdin and prints JSON, so it is not Claude-specific, and it fails open: input it cannot parse allows the read.
