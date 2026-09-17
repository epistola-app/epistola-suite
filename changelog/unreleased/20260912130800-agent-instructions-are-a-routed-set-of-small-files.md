---
type: docs
scopes: [agents]
audience: dev
title: Agent instructions are a routed set of small files instead of one 56 KB page.
---

`CLAUDE.md` had grown to 588 lines that every session loaded in full, most of it irrelevant to any one task, none of it reachable by other agents, and its size alone put it past the limit Codex reads. `AGENTS.md` is now canonical and small: the stability contract, the repository map, the idioms that hold everywhere, a verify loop, a definition of done, and a table routing each area to its own guide and naming the test that enforces it. Nine area guides sit next to the code they govern, and five rule cards carry `paths:` frontmatter so the markup, test, migration, bundled-catalog and configuration rules arrive when a matching file is touched. Skills moved to `.agents/skills/`, where every agent finds them, and the outdated ones were rewritten or removed. The review behind all of this is recorded in [`docs/agent-effectiveness-review.md`](docs/agent-effectiveness-review.md).
