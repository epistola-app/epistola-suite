---
type: build
scopes: [changelog]
audience: dev
title: Changelog entries are one file per change.
---

`CHANGELOG.md` had grown to 744 KB, and since every entry was prepended to the same spot, adding one meant opening the whole file and concurrent branches all collided there — resolved silently by a union merge that could interleave entries. Notable changes now add a small file under `changelog/unreleased/` instead, validated by `checkChangelogFragments` and assembled into the released history at release time. The in-app dialog reads a rendered file that still contains both, so it keeps one parser and looks no different.
