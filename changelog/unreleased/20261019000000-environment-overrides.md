---
type: feat
scopes: [environments, generation, api]
title: An environment can use a chosen release, or follow work in progress, as its latest version.
---

On an environment's page, **Use as latest** makes one of the releases it serves the one documents there get, for a rollback or to hold back a newer release; **Use the highest again** undoes it. A request naming an exact version still gets that version; one naming none, or a range, gets the chosen one.

A test environment can also **follow** the next version or long-term work: choose **Try it on an environment…** in the version menu. Documents made there come from the work as it is changed, with no publishing in between, validated against its data contract, marked as a preview, and saying which work they came from. The long-term work banner names the environments following it.

Environments can be marked **production**, and then use only published versions: they cannot follow work in progress. Existing environments keyed or named `production`, `prod`, `prd` or `productie` are marked when upgrading.

The environment's history records each change. REST: `setEnvironmentOverride`, `clearEnvironmentOverride`, `listEnvironmentOverrides`, `EnvironmentDto.production`, and `draftId`/`draftLabel` on documents rendered from a draft.
