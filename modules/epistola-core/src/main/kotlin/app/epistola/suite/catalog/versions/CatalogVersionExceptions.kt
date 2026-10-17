// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.common.ids.CatalogKey

/** The catalog has no version with that number. */
class CatalogVersionNotFoundException(val catalogKey: CatalogKey, val version: String) : RuntimeException("Catalog '${catalogKey.value}' has no version $version")

/** Only a draft can be changed, renumbered or deleted; [version] is released. */
class CatalogVersionReleasedException(val catalogKey: CatalogKey, val version: String) : RuntimeException("Version $version of catalog '${catalogKey.value}' is released and cannot change")

/** A version number is unique in its catalog, across drafts and releases. */
class CatalogVersionInUseException(val catalogKey: CatalogKey, val version: String) : RuntimeException("Catalog '${catalogKey.value}' already has a version $version")

/**
 * A draft that is behind releases (released versions above its base and below its number, which it
 * does not contain unless an author applied their changes by hand) is released only when every one of
 * them is acknowledged (contract ADR 0002). [behind] are the ones not acknowledged.
 */
class CatalogVersionBehindException(val catalogKey: CatalogKey, val version: String, val behind: List<String>) : RuntimeException("Version $version of catalog '${catalogKey.value}' is behind release ${behind.joinToString()}; acknowledge ${if (behind.size == 1) "it" else "them"} to release it")
