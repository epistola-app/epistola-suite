---
type: chore
scopes: [agents]
audience: dev
title: Skills moved to where every agent can find them.
---

The nine skills now live in `.agents/skills/`, which Codex scans from the working directory up to the repository root, and `.claude/skills/` holds a symlink to each so Claude Code sees exactly what it saw before. `.aiignore` no longer hides `.agents/`. Their content is unchanged — this is only where they sit — but until now a non-Claude agent had no skills at all in this repository, and the ones it could not see were precisely those describing how to write a command, a page or a test.
