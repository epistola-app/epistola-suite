---
type: fix
scopes: [catalog, environments]
audience: user
title: Discarding a draft names the environments that stop serving it.
---

An environment serving a draft stops serving it when the draft is discarded, and serves nothing of
that catalog until a release is deployed there. The confirmation said nothing of it, and claimed
nothing else was affected. It now names those environments, on the draft's page and on the Versions
list, and the MCP `discard_draft` tool tells an assistant to name them before discarding.
