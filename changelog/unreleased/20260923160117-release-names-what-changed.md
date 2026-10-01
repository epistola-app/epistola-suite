---
type: feat
scopes: [catalog]
audience: user
issues: [988]
title: Releasing a catalog says which resources it will change.
---

The release dialog said only that the working copy had unreleased changes, never which ones — so
releasing meant trusting that everything which had accumulated since the last release was meant to
ship. It now names them: what this release adds, updates and drops, and how much it carries over
unchanged.

A release records the per-resource digests of what it contained, which is what the next one compares
against. A catalog whose last release predates that says so in one sentence rather than listing
every resource as new; the next release restores the detail.
