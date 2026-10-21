---
type: feat
scopes: [templates, catalog, api, mcp]
audience: user
title: A template has a description, and a catalog keeps it.
---

A template carries plain-text notes for its authors: what it is for, design decisions, known gaps
(at most 2000 characters, never rendered). It is edited on the template's settings tab, set through
REST (`description` on create and update; an empty string removes it) and through the MCP
`create_template` and `update_template` tools, and travels with the template in its catalog (wire v8
`TemplateResource.description`). Importing, copying a release into a draft, merging and exporting
now keep it, and keep a variant's producer `metadata` as it came, so a draft started from a release
no longer loses either and a merge no longer reports such a template as changed when only that was
missing. An upgrade preview of a catalog with a source now tells a template whose only change is its
description (or another member wire v8 added) apart as changed.
