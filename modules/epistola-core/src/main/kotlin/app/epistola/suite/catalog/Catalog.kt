// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.protocol.AttributeAssignment
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogLicense
import app.epistola.catalog.protocol.CatalogPresentation
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.crypto.Secret
import org.jdbi.v3.core.mapper.reflect.ColumnName
import org.jdbi.v3.json.Json
import java.time.OffsetDateTime

data class Catalog(
    /** The catalog's key within its namespace; column `catalog_key`. */
    @ColumnName("catalog_key")
    val id: CatalogKey,
    val tenantKey: TenantKey,
    /** The registry the catalog is named on (contract ADR 0003). */
    val registry: String,
    /** The catalog's namespace on [registry] (contract ADR 0003). */
    val namespace: String,
    val name: String,
    val description: String? = null,
    val type: CatalogType,
    val sourceUrl: String? = null,
    val sourceAuthType: AuthType = AuthType.NONE,
    /** Encrypted at rest via the JDBI [Secret] mappers; plaintext only in memory. */
    val sourceAuthCredential: Secret? = null,
    val installedReleaseVersion: String? = null,
    val installedFingerprint: String? = null,
    /**
     * Per-resource source-side digests (`"type/slug"` -> SHA-256) of the
     * installed release, captured from the source manifest at register/upgrade.
     * The source-vs-source baseline for [PreviewCatalogUpgrade][app.epistola.suite.catalog.queries.PreviewCatalogUpgrade].
     * Never publisher-authored; null for AUTHORED catalogs.
     */
    @Json val installedResourceFingerprints: Map<String, String>? = null,
    @Json val catalogMetadata: CatalogMetadata = CatalogMetadata(),
    val installedAt: OffsetDateTime? = null,
    val releasedVersion: String? = null,
    val releasedFingerprint: String? = null,
    val releasedAt: OffsetDateTime? = null,
    val exchangePublicationPolicy: CatalogPublicationPolicy = CatalogPublicationPolicy.INHERIT,
    /**
     * When catalog content was last set wholesale by a ZIP import. With
     * [releasedAt] it forms the AUTHORED drift baseline
     * `GREATEST(releasedAt, importedAt)`: a resource changed after it = the
     * working copy has unreleased changes (the catalog list's "pending
     * changes" hint). A no-op re-import advances this in lockstep with the
     * imported resources, so it does not register as drift.
     */
    val importedAt: OffsetDateTime? = null,
    val contentUpdatedAt: OffsetDateTime,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

/** This catalog's full name. */
val Catalog.catalogName: CatalogName get() = CatalogName(registry, namespace, id)

/**
 * A catalog's full name (contract ADR 0003): its key means nothing on its own, since two publishers
 * can each have a catalog called `letters`. Messages and logs name a catalog by this.
 */
data class CatalogName(
    val registry: String,
    val namespace: String,
    val key: CatalogKey,
) {
    /** The canonical name, `registry/namespace/key` (contract ADR 0004). */
    override fun toString(): String = "$registry/$namespace/${key.value}"
}

/** Catalog metadata that is part of the portable catalog manifest. */
data class CatalogMetadata(
    val attributes: List<AttributeAssignment> = emptyList(),
    val keywords: Set<String> = emptySet(),
    val presentation: CatalogPresentation? = null,
    val license: CatalogLicense? = null,
) {
    fun toCatalogInfo(
        slug: String,
        name: String,
        description: String?,
        availableAssetSlugs: Set<String>? = null,
        namespace: String? = null,
        registry: String? = null,
    ): CatalogInfo {
        val effectivePresentation = if (availableAssetSlugs == null) {
            presentation
        } else {
            presentation?.let {
                CatalogPresentation(
                    iconImageKey = it.iconImageKey?.takeIf(availableAssetSlugs::contains),
                    imageKeys = it.imageKeys.filter(availableAssetSlugs::contains),
                )
            }
        }
        return CatalogInfo.create(
            key = slug,
            name = name,
            description = description,
            attributes = attributes,
            keywords = keywords,
            presentation = effectivePresentation,
            license = license,
            namespace = namespace,
            registry = registry,
        )
    }

    companion object {
        fun from(info: CatalogInfo): CatalogMetadata = CatalogMetadata(
            attributes = info.attributes,
            keywords = info.keywords,
            presentation = info.presentation,
            license = info.license,
        )
    }
}

enum class CatalogType {
    AUTHORED,
    SUBSCRIBED,
}

enum class AuthType {
    NONE,
    API_KEY,
    BEARER,
}

/**
 * Controls the default and override rules for publishing releases to Exchange.
 *
 * [label] is the single wording every surface shows, so the option list cannot drift between the
 * edit form and the places that display the current choice.
 */
enum class CatalogPublicationPolicy(val label: String) {
    INHERIT("Inherit tenant default"),
    ALWAYS("Always publish (no release override)"),
    DEFAULT_YES("Publish by default"),
    DEFAULT_NO("Do not publish by default"),
    NEVER("Never publish (no release override)"),
    ;

    fun allowsReleaseOverride(): Boolean = this !in setOf(ALWAYS, NEVER)

    fun defaultPublish(tenantDefault: Boolean): Boolean = when (this) {
        INHERIT -> tenantDefault
        ALWAYS, DEFAULT_YES -> true
        DEFAULT_NO, NEVER -> false
    }

    fun resolve(tenantDefault: Boolean, releaseOverride: Boolean?): Boolean = when (this) {
        ALWAYS -> true
        NEVER -> false
        else -> releaseOverride ?: defaultPublish(tenantDefault)
    }

    companion object {
        /**
         * The one definition of a valid Exchange namespace slug — mirrored by the `catalogs`
         * CHECK constraint and reused as the browser-side `pattern`, so the three cannot diverge.
         */
        const val NAMESPACE_PATTERN = "[a-z][a-z0-9-]{0,62}"
    }
}
