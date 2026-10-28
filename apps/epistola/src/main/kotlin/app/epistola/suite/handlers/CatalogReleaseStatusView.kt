// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.CATALOG_SCHEMA_VERSION
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogUpstreamCheck
import app.epistola.suite.catalog.CatalogUpstreamCheckFailure
import app.epistola.suite.catalog.UpstreamAvailability
import app.epistola.suite.catalog.queries.GetCatalogUpstreamStates
import app.epistola.suite.catalog.queries.ListCatalogsForManagement
import app.epistola.suite.catalog.registry.ListReleaseRegistries
import app.epistola.suite.catalog.registry.RegistrySourceUri
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.exchange.ExchangeSourceUri
import app.epistola.suite.exchange.ResolveCatalogInstallAvailability
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.mediator.query

/**
 * What the Version cell of the catalogs list says about one catalog with a source: the release it
 * is on, and what its source offers ([Catalog.hasSource], ADR 0032).
 *
 * Resolved on the server so the template asks by property name rather than re-deriving the rule.
 * It is the same shape whether the answer came from the recorded background check (first paint) or
 * from someone pressing the check button, which is what lets one fragment serve both.
 *
 * The states are deliberately the ones that already existed, plus two the Exchange path introduced:
 * a source can now say that the release you are *on* has been withdrawn, and it can name a version
 * without being able to say anything about the wire format.
 */
data class CatalogReleaseStatusView(
    val state: String,
    val installedVersion: String?,
    val availableVersion: String?,
    val sourceSchemaVersion: Int?,
    val currentSchemaVersion: Int?,
    val detail: String?,
    /** Whether applying an upgrade goes through the Exchange dialog rather than the manifest diff. */
    val fromExchange: Boolean,
    /** Where a catalog installed from a container registry gets its releases; null for any other. */
    val registrySource: RegistrySourceUri? = null,
) {
    companion object {
        const val UNCHECKED = "UNCHECKED"
        const val UP_TO_DATE = "UP_TO_DATE"
        const val UPDATE_AVAILABLE = "UPDATE_AVAILABLE"
        const val ZIP_MANAGED = "ZIP_MANAGED"
        const val CHECK_FAILED = "CHECK_FAILED"
        const val NOT_IN_SYNC = "NOT_IN_SYNC"
        const val SOURCE_AHEAD = "SOURCE_AHEAD"
        const val WITHDRAWN = "WITHDRAWN"

        /**
         * Derives the cell from a catalog and whatever the last check recorded.
         *
         * Order matters. A withdrawn installed release outranks an available upgrade because it is
         * the more urgent fact about the same catalog, and a schema mismatch outranks both: a
         * mirror is never migrated locally, so offering an upgrade nobody can apply would be a
         * button that only ever fails.
         */
        fun of(
            catalog: Catalog,
            check: CatalogUpstreamCheck?,
            currentSchemaVersion: Int,
            fromExchange: Boolean,
        ): CatalogReleaseStatusView {
            // The catalog's highest release, received or released here, is what the source's offer is
            // newer than or not, and what the cell shows.
            val latest = catalog.latestReleaseVersion
            val base = CatalogReleaseStatusView(
                state = UNCHECKED,
                installedVersion = latest,
                availableVersion = null,
                sourceSchemaVersion = null,
                currentSchemaVersion = currentSchemaVersion,
                detail = null,
                fromExchange = fromExchange,
            )
            if (!catalog.hasSource) return base.copy(state = ZIP_MANAGED)
            if (check == null) return base

            val sourceSchema = check.availableSchemaVersion
            val state = when {
                check.failure == CatalogUpstreamCheckFailure.SCHEMA_TOO_NEW -> SOURCE_AHEAD
                check.failure != null -> CHECK_FAILED
                check.upstreamAvailability == UpstreamAvailability.WITHDRAWN -> WITHDRAWN
                sourceSchema != null && sourceSchema < currentSchemaVersion -> NOT_IN_SYNC
                sourceSchema != null && sourceSchema > currentSchemaVersion -> SOURCE_AHEAD
                check.upgradeAvailable(latest) -> UPDATE_AVAILABLE
                check.availableVersion != null -> UP_TO_DATE
                else -> UNCHECKED
            }
            return base.copy(
                state = state,
                availableVersion = check.availableVersion,
                sourceSchemaVersion = sourceSchema,
                detail = check.failure?.message,
            )
        }
    }
}

/**
 * Everything the catalogs list needs, in one place so the page and the dialogs that refresh it
 * out-of-band cannot drift apart.
 *
 * Two queries, no remote calls. The Version cell used to be blank on arrival and filled in by a
 * round-trip per row; it now renders from what the background check already recorded.
 */
internal fun ModelBuilder.catalogListModel(tenantKey: TenantKey) {
    val rows = ListCatalogsForManagement(tenantKey).query()
    val upstream = GetCatalogUpstreamStates(tenantKey).query()
    "tenantId" to tenantKey
    "catalogs" to rows
    // Every row links its catalog by address (ADR 0034, section 9).
    // Pair() rather than `to`: inside this builder `to` is the model-binding infix, not Kotlin's.
    // By the catalog itself, not its key: two catalogs can share a key (ADR 0034).
    "versionStatus" to rows.associate { row ->
        Pair(
            row.catalog,
            CatalogReleaseStatusView.of(
                row.catalog,
                upstream[row.catalog.catalogId],
                CATALOG_SCHEMA_VERSION,
                fromExchange = ExchangeSourceUri.matches(row.catalog.sourceUrl),
            ).copy(registrySource = RegistrySourceUri.parse(row.catalog.sourceUrl)),
        )
    }
    "exchangeInstallAvailable" to ResolveCatalogInstallAvailability(tenantKey).query()
    "registryInstallAvailable" to CatalogRegistryHandler.canInstall(tenantKey, ListReleaseRegistries(tenantKey).query())
}
