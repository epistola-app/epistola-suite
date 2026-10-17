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
 * A catalog has one draft until drafts keep their own content: a second draft cannot be opened and
 * the one draft cannot be discarded. Its number must stay above every release, the order it is released in.
 */
class SingleCatalogDraftException(val catalogKey: CatalogKey, message: String) : RuntimeException(message)
