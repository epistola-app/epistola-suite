package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogNotUpgradeableException
import app.epistola.suite.catalog.CatalogUpgradeAnalyzer
import app.epistola.suite.catalog.InstalledResource
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component

/**
 * Previews what a subscriber-triggered upgrade of a SUBSCRIBED catalog would
 * do — **without applying it**. Re-fetches the source manifest and classifies
 * every `(type, slug)` against the live installed working copy:
 *
 *  - **ADDED**   — in the new release, not installed (by-design *not* upgraded
 *    automatically; surfaced so the UI can offer an opt-in install).
 *  - **REMOVED**  — installed, no longer in the manifest (would be deleted).
 *  - **CHANGED**  — installed and in the manifest, content fingerprint differs.
 *  - **UNCHANGED** — installed and in the manifest, fingerprint identical.
 *
 * `CHANGED` uses the **same** per-resource canonical digest as the whole-catalog
 * fingerprint ([CatalogFingerprintService]), so the preview can never disagree
 * with what an actual [UpgradeCatalog][app.epistola.suite.catalog.commands.UpgradeCatalog]
 * would re-install. Cross-catalog [conflicts] for the REMOVED set are computed
 * with the **shared** [CatalogUpgradeAnalyzer] that `UpgradeCatalog` throws on,
 * so a conflict surfaces here up front instead of only at apply time.
 *
 * Read-only: this query never mutates. Modeled on
 * [PreviewInstall].
 */
data class PreviewCatalogUpgrade(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<UpgradeDiff>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

data class UpgradeResourceChange(val type: String, val slug: String)

data class UpgradeDiff(
    val catalogKey: CatalogKey,
    val previousVersion: String?,
    val newVersion: String,
    val added: List<UpgradeResourceChange>,
    val removed: List<UpgradeResourceChange>,
    val changed: List<UpgradeResourceChange>,
    val unchanged: List<UpgradeResourceChange>,
    /** Human-readable cross-catalog conflicts blocking the REMOVED set. */
    val conflicts: List<String>,
) {
    /** ADDED/REMOVED/CHANGED — UNCHANGED alone means "already current". */
    val hasChanges: Boolean get() = added.isNotEmpty() || removed.isNotEmpty() || changed.isNotEmpty()
    val hasConflicts: Boolean get() = conflicts.isNotEmpty()
}

@Component
class PreviewCatalogUpgradeHandler(
    private val fingerprintService: CatalogFingerprintService,
    private val analyzer: CatalogUpgradeAnalyzer,
    private val catalogClient: CatalogClient,
) : QueryHandler<PreviewCatalogUpgrade, UpgradeDiff> {

    override fun handle(query: PreviewCatalogUpgrade): UpgradeDiff {
        val catalog = GetCatalog(query.tenantKey, query.catalogKey).query()
            ?: throw CatalogNotFoundException(query.catalogKey)

        val sourceUrl = catalog.sourceUrl
            ?: throw CatalogNotUpgradeableException(
                query.catalogKey,
                "not a subscribed catalog (no source URL) — only subscribed catalogs can be upgraded",
            )

        val manifest = catalogClient.fetchManifest(sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential?.value)

        val incoming = fingerprintService.perResourceFingerprintsFromSource(
            sourceUrl,
            catalog.sourceAuthType,
            catalog.sourceAuthCredential?.value,
        )

        // Source-vs-source: the baseline was captured from the source manifest
        // at register/upgrade (same provenance as the incoming side), so a
        // CHANGED verdict means the publisher changed that resource — never
        // install round-trip noise. SUBSCRIBED catalogs always have it.
        val installed = catalog.installedResourceFingerprints
            ?: throw CatalogNotUpgradeableException(
                query.catalogKey,
                "no per-resource baseline captured yet — re-register or upgrade it to capture one",
            )

        fun parse(key: String) = UpgradeResourceChange(
            type = key.substringBefore('/'),
            slug = key.substringAfter('/'),
        )

        val added = (incoming.keys - installed.keys).sorted().map(::parse)
        val removed = (installed.keys - incoming.keys).sorted().map(::parse)
        val shared = incoming.keys intersect installed.keys
        val changed = shared.filter { incoming[it] != installed[it] }.sorted().map(::parse)
        val unchanged = shared.filter { incoming[it] == installed[it] }.sorted().map(::parse)

        // Same conflict logic UpgradeCatalog throws on, on the same set it would
        // delete — so a blocking conflict is visible before Apply, not after.
        val conflicts = analyzer.findConflicts(
            query.tenantKey,
            query.catalogKey,
            removed.map { InstalledResource(it.type, it.slug) },
        )

        return UpgradeDiff(
            catalogKey = query.catalogKey,
            previousVersion = catalog.installedReleaseVersion,
            newVersion = manifest.release.version,
            added = added,
            removed = removed,
            changed = changed,
            unchanged = unchanged,
            conflicts = conflicts,
        )
    }
}
