// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets

import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * The set of allowed asset media types, sourced from the seeded `asset_types`
 * lookup table (not a Kotlin enum). This is the validation authority at the
 * asset write boundary: adding a new type is a DB insert, no code change.
 *
 * The set only changes via a migration/seed (which requires a restart), so it
 * is loaded once on first use and held for the process lifetime.
 */
@Component
class AssetTypeCatalog(private val jdbi: Jdbi) {

    @Volatile
    private var allowed: Set<String>? = null

    private fun allowedTypes(): Set<String> = allowed ?: synchronized(this) {
        allowed ?: jdbi.withHandle<Set<String>, Exception> { handle ->
            handle.createQuery("SELECT media_type FROM asset_types")
                .mapTo(String::class.java)
                .toSet()
        }.also { allowed = it }
    }

    fun isAllowed(mimeType: String): Boolean = mimeType in allowedTypes()

    /**
     * Returns the validated [AssetMediaType], or throws
     * [UnsupportedAssetTypeException] (listing the allowed types) when the
     * MIME string is not a registered asset type.
     */
    fun require(mimeType: String): AssetMediaType {
        if (!isAllowed(mimeType)) {
            throw UnsupportedAssetTypeException(mimeType, allowedTypes())
        }
        return AssetMediaType(mimeType)
    }
}
