// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.revisions.ReleaseDescription
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * A received release renders with rendering defaults this installation does not have: newer than
 * [supported], or a version it never had. Rendering it with other defaults would change its
 * documents, so it is refused until Epistola is upgraded.
 */
class ReleaseRenderingDefaultsTooNewException(
    val catalogKey: CatalogKey,
    val version: String,
    /** The rendering defaults version the release names, as it names it. */
    val renderingDefaultsVersion: String,
    val supported: Int = RenderingDefaults.CURRENT.version,
) : RuntimeException(
    "${catalogKey.value} $version renders with rendering defaults version $renderingDefaultsVersion, which this " +
        "Epistola does not have (it has up to $supported). Upgrade Epistola to receive this release.",
)

/**
 * What a release received from elsewhere keeps of what it says it is (catalog wire v8): its id, the
 * rendering defaults it renders with, and the release it was made from. Checked before anything of
 * it is written, by every way a release arrives, and applied when it is recorded
 * ([RecordInstalledRelease]).
 */
@Component
class ReceivedReleases(private val jdbi: Jdbi) {

    /**
     * Refuses [release] of [catalogKey] when it cannot be kept as it says it is: its id names another
     * release (or a draft) of the catalog here ([CatalogImportConflictException]), or it renders with
     * rendering defaults this installation lacks ([ReleaseRenderingDefaultsTooNewException]).
     */
    fun requireKeepable(catalog: CatalogId, release: ReleaseInfo) {
        renderingDefaultsVersion(catalog.key, release)
        jdbi.useHandle<Exception> { requireIdFree(it, catalog, release) }
    }

    /**
     * The rendering defaults version [release] renders with: the one its metadata names, when this
     * installation has it, else (it names none) the current one.
     *
     * @throws ReleaseRenderingDefaultsTooNewException when it names one this installation lacks.
     */
    fun renderingDefaultsVersion(catalogKey: CatalogKey, release: ReleaseInfo): Int = when (val claim = ReleaseDescription.renderingDefaultsVersion(release.metadata)) {
        null -> RenderingDefaults.CURRENT.version

        is ReleaseDescription.RenderingDefaultsClaim.Version -> claim.version.takeIf { known(it) }
            ?: throw ReleaseRenderingDefaultsTooNewException(catalogKey, release.version, claim.version.toString())

        is ReleaseDescription.RenderingDefaultsClaim.Unreadable -> throw ReleaseRenderingDefaultsTooNewException(catalogKey, release.version, claim.value)
    }

    /**
     * Refuses [release] when its id is the id of another release of [catalogKey] here, or of a draft:
     * one id names one release, everywhere (contract `CatalogReleaseIds`). The same release, by
     * number, keeps its id.
     */
    fun requireIdFree(handle: Handle, catalog: CatalogId, release: ReleaseInfo) {
        val id = release.releaseId ?: return
        val holder = handle.createQuery(
            """
            SELECT 'release ' || version FROM catalog_releases
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version_key = :id AND version <> :v
            UNION ALL
            SELECT 'a draft' FROM catalog_versions
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND state = 'draft' AND version_key = :id
            LIMIT 1
            """,
        )
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .bind("id", id)
            .bind("v", release.version)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null) ?: return
        throw CatalogReleaseTakenException(
            "${catalog.key.value} ${release.version} has the release id '$id', which $holder of this catalog has here; one id names one release.",
        )
    }

    private fun known(version: Int): Boolean = version <= RenderingDefaults.CURRENT.version && runCatching { RenderingDefaults.forVersion(version) }.isSuccess
}
