---
type: feat
scopes: [quality]
audience: user
breaking: true
maturity: alpha
title: Quality findings belong to the catalog version they were checked in.
---

A check is stored under what it read: the variant, its template, the theme, stencils and fonts the
version resolves, and the tenant's language. Each draft and release shows the findings checked against
what it holds, so a draft cut from a release shows the release's findings at once, editing one variant
leaves the others current, and a finding checked against something that has changed since is marked
outdated rather than shown as current. Saves, ready marks and "Check now" check the draft they were
made in.

A check's finding the check no longer reports is gone, not kept as resolved; only a note a person raised
is resolved, and it shows in every version. Comments belong to the problem, as ignores do, so a
discussion holds across versions. Upgrading keeps every note, comment and ignore; findings from before
2.0 show as outdated until the variant is checked again, and resolved ones are not kept.
