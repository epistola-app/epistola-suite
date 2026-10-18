// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.common.ids.CatalogKey

/** The catalog has no version with that number. */
class CatalogVersionNotFoundException(val catalogKey: CatalogKey, val version: String) : RuntimeException("Catalog '${catalogKey.value}' has no version $version")

/** Only a draft can be changed or discarded; [version] is released. */
class CatalogVersionReleasedException(val catalogKey: CatalogKey, val version: String) : RuntimeException("Version $version of catalog '${catalogKey.value}' is released and cannot change")

/**
 * A draft that is behind releases (released versions above its base, which it does not contain
 * unless an author applied their changes by hand) is released only when every one of them is
 * acknowledged (contract ADR 0002). [version] names the draft; [behind] are the releases not
 * acknowledged.
 */
class CatalogVersionBehindException(val catalogKey: CatalogKey, val version: String, val behind: List<String>) : RuntimeException("Version $version of catalog '${catalogKey.value}' is behind release ${behind.joinToString()}; acknowledge ${if (behind.size == 1) "it" else "them"} to release it")

/**
 * Draft [version] is the only version holding a resource something outside it still needs, so
 * discarding it would delete that resource's identity out from under it. [holders] name each one and
 * the resource it needs, e.g. `the tenant's default theme (theme letters/seasonal)`.
 */
class CatalogVersionHeldException(val catalogKey: CatalogKey, val version: String, val holders: List<String>) :
    RuntimeException(
        "Draft $version of catalog '${catalogKey.value}' cannot be discarded: it alone holds what " +
            "${holders.joinToString("; ")} ${if (holders.size == 1) "uses" else "use"}. Change ${if (holders.size == 1) "it" else "them"} first.",
    )
