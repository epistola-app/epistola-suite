---
type: docs
scopes: [agents]
audience: dev
title: The conventions are a routed set of small files instead of one 56 KB page.
---

`CLAUDE.md` had grown to 588 lines that every session loaded in full, most of it irrelevant to any one task and none of it reachable by other agents — and its size alone put it past the limit Codex reads. `AGENTS.md` is now the canonical file at 149 lines: the stability contract, the repository map, the idioms that hold everywhere, a verify loop, a definition of done, and a table routing each area to its own guide and naming the test that enforces it. `CLAUDE.md` imports it and adds only what is Claude-specific. Nine area guides sit next to the code they govern, so they load when that code is opened; five rule cards carry `paths:` frontmatter so the markup, test, migration, bundled-catalog and configuration rules arrive when a matching file is touched. Nothing was invented: the text is the corrected conventions, redistributed, with the last stale `epistola-core/api` reference removed. Putting a guide in `vulnerabilities/` also exposed a latent assumption in `scripts/vulnerability_advisories.py`, which read every Markdown file there as an advisory record and failed on the first one that was not; it now recognises records by their dated filename, so companion prose can sit beside them.
