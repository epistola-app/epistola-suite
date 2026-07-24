<!--
  SPDX-FileCopyrightText: Epistola Nederland B.V.

  SPDX-License-Identifier: AGPL-3.0-only
-->

# Catalog wire format — v4

> Part of [catalog import/export](../README.md). This folder documents the **catalog-wide wire format at `schemaVersion` 4** — one file per part.

A catalog export/import is a `catalog.json` [manifest](manifest.md) plus one detail file per resource. **All parts share a single, catalog-wide `schemaVersion`** (`4`): the manifest is authoritative for it and every resource detail carries the same number, so each file is self-describing but the whole bundle moves together. There is no independent per-resource version. See [ADR 0007](../../adr/0007-catalog-wire-format-migrations.md).

## Parts

| Part                                     | Carries                                                                           |
| ---------------------------------------- | --------------------------------------------------------------------------------- |
| [Manifest](manifest.md) (`catalog.json`) | catalog identity, `release` (version + fingerprint), resource index, dependencies |
| [Asset](asset.md)                        | image metadata + `contentUrl` to the binary                                       |
| [Code list](code-list.md)                | reusable enumerations (`entries`)                                                 |
| [Font](font.md)                          | asset-backed font family + variants                                               |
| [Attribute](attribute.md)                | variant attribute + `codeListBinding`                                             |
| [Theme](theme.md)                        | document styles, page settings, presets                                           |
| [Stencil](stencil.md)                    | published fragment (`content` + `version`)                                        |
| [Template](template.md)                  | `templateModel`, `dataModel`, variants                                            |

(Rows are in install order — see [`CatalogConstants.RESOURCE_INSTALL_ORDER`](../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogConstants.kt).)

## Versioning

When a non-round-trip-compatible shape change bumps `CATALOG_SCHEMA_VERSION`, this whole folder is copied to `v5/`, the changed part(s) are edited there, and a migration step lands in the single chain ([ADR 0007](../../adr/0007-catalog-wire-format-migrations.md)). The old folder stays in place so the entire wire format diffs against its predecessor as a set.
