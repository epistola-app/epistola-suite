---
type: docs
scopes: [agents]
audience: dev
title: Six outdated assistant skills are removed in favour of the docs.
---

`command-query`, `contract-change`, `editor-component`, `htmx-form`, `ui-test` and `unit-test` each taught something the build now rejects — commands without an authorization marker, inline `onclick` handlers under the strict CSP, Playwright calls `UiTestHygieneTest` bans, a test base class removed in April. Their subjects are already covered by maintained pages, so `CLAUDE.md` gains a "Where to look" table routing each area to its doc and naming the test that enforces it, several of which were never mentioned anywhere. Git history keeps the old text for the planned rewrites. The stray duplicate of the MCP reference file under `pr-review/` is gone.
