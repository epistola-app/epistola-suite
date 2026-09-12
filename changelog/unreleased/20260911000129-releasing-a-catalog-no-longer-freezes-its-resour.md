---
type: feat
scopes: [catalogs]
audience: user
title: Releasing a catalog no longer freezes its resources.
---

A released source catalog produced a blocker, so a single local release — one nobody had ever pulled — made every resource in that catalog permanently unmovable. It is now a `released-source` warning: the move is well-defined locally, and whether a subscriber is affected is the operator's judgement. Previews carry warnings alongside blockers, and a warning appearing between preview and execute invalidates the plan just as a blocker does.
