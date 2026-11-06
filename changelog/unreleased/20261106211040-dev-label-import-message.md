---
type: fix
scopes: [catalog]
audience: user
title: Importing a 1.x archive labelled -dev says what the label means and what to do.
---

Epistola 1.x exported a catalog that was never released, or had changes since its last release,
with a `-dev` version (`0.0.0-dev`, `1.2.0-dev`). Release versions are `MAJOR.MINOR.PATCH` from 2.0,
so importing such an archive is refused, but the refusal only said what the number must be. It now
says the archive is an unreleased export, and offers two ways on: release the catalog in the
installation it came from and export it again, or set `release.version` in the archive's
`catalog.json` to a version no other release of the catalog has, such as the next patch.
