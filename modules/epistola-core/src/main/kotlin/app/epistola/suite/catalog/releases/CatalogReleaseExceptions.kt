// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.common.ids.CatalogKey

/** The catalog has no release with that number, nor a draft by that key. */
class CatalogReleaseNotFoundException(val catalogKey: CatalogKey, val version: String) : RuntimeException("Catalog '${catalogKey.value}' has no release or draft $version")

/**
 * Only a draft can be changed or discarded; [version] is released (ADR 0036: a release is its frozen
 * row). Also what a save through a draft's id gets once that draft was released: the next draft is
 * where the work goes on.
 */
class CatalogReleaseIsReleasedException(val catalogKey: CatalogKey, val version: String) : RuntimeException("This version of catalog '${catalogKey.value}' has been released as $version and cannot change; open the next draft to go on.")

/**
 * A draft that is behind releases (contract ADR 0002, "A draft can be behind": releases it does not
 * contain unless an author applied their changes by hand) is released only when every one of them is
 * acknowledged. [version] names the draft; [behind] are the releases not acknowledged.
 */
class CatalogReleaseBehindException(val catalogKey: CatalogKey, val version: String, val behind: List<String>) : RuntimeException("Draft $version of catalog '${catalogKey.value}' is behind release ${behind.joinToString()}; acknowledge ${if (behind.size == 1) "it" else "them"} to release it")

/**
 * Draft [version] is the only draft or release holding a resource something outside it still needs, so
 * discarding it would delete that resource's identity out from under it. [holders] name each one and
 * the resource it needs, e.g. `the tenant's default theme (theme letters/seasonal)`.
 */
class CatalogReleaseHeldException(val catalogKey: CatalogKey, val version: String, val holders: List<String>) :
    RuntimeException(
        "Draft $version of catalog '${catalogKey.value}' cannot be discarded: it alone holds what " +
            "${holders.joinToString("; ")} ${if (holders.size == 1) "uses" else "use"}. Change ${if (holders.size == 1) "it" else "them"} first.",
    )

/**
 * Draft [version] has nothing to release: nothing changed against its base [base], no resource and no
 * catalog-level content (contract `releaseCatalogDraft`, ADR 0034). Its release would be the base
 * again under another number.
 */
class CatalogDraftUnchangedException(val catalogKey: CatalogKey, val version: String, val base: String) : RuntimeException("Draft $version of catalog '${catalogKey.value}' has nothing to release: nothing changed against $base")

/** A draft of another catalog that a dependency declaration names, which a release cannot pin. */
data class UnreleasedDependency(val registry: String, val namespace: String, val catalogKey: String, val versionId: String, val draftName: String)

/**
 * The draft's dependency declarations name drafts of other catalogs ([unreleased], directly or
 * through those drafts' own declarations), and the release was not asked to release them along
 * (contract `release-dependencies-unreleased`).
 */
class CatalogDependenciesUnreleasedException(val catalogKey: CatalogKey, val unreleased: List<UnreleasedDependency>) :
    RuntimeException(
        "${catalogKey.value} uses drafts of other catalogs: " +
            unreleased.joinToString { "${it.namespace}/${it.catalogKey} (${it.draftName})" } +
            ". Release them first, or release them along.",
    )

/** Draft [version] is named by another catalog's draft ([namedBy]), so it cannot be discarded. */
class CatalogDraftInUseException(val catalogKey: CatalogKey, val version: String, val namedBy: List<String>) : RuntimeException("Draft $version of catalog '${catalogKey.value}' cannot be discarded: ${namedBy.joinToString()} ${if (namedBy.size == 1) "builds" else "build"} on it.")
