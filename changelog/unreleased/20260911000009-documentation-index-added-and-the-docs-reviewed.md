---
type: docs
scopes: [readme]
audience: dev
title: Documentation index added and the docs reviewed for consistency.
---

[`docs/README.md`](docs/README.md) indexes every page under `docs/` by topic, labelling each as current behavior, alpha/beta, a design proposal, or a point-in-time record; [`docs/adr/README.md`](docs/adr/README.md) and [`docs/plans/README.md`](docs/plans/README.md) index the decision records and the historical plans. The root `README.md` and `CONTRIBUTING.md` link it, and both the root README and the index now point at <https://epistola.app/en/learn> for user documentation. Status markers are now one form (a `> **Status:** …` banner under the title) across every non-current page, ADR 0001 and 0005 are marked accepted and implemented rather than proposed, broken and stale links are fixed (`api-spec` → `rest-api`, the dangling `api-keys.md`, load-test and stencil migration paths, `EntityId`/`JdbiSlugIdSupport` now in `epistola-core`), the OpenAPI-spec location is corrected to the external `epistola-contract`, and the two snake_case plan files are renamed to kebab-case.
