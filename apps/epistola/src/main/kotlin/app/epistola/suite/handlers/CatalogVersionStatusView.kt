// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.CATALOG_SCHEMA_VERSION
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.CatalogUpstreamCheck
import app.epistola.suite.catalog.CatalogUpstreamCheckFailure
import app.epistola.suite.catalog.UpstreamAvailability
import app.epistola.suite.catalog.queries.GetCatalogUpstreamStates
import app.epistola.suite.catalog.queries.ListCatalogsForManagement
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.exchange.ExchangeSourceUri
import app.epistola.suite.exchange.ResolveCatalogInstallAvailability
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.mediator.query

/**
 * What the Version cell of the catalogs list says about one subscribed catalog.
 *
 * Resolved on the server so the template asks by property name rather than re-deriving the rule.
 * It is the same shape whether the answer came from the recorded background check (first paint) or
 * from someone pressing the check button, which is what lets one fragment serve both.
 *
 * The states are deliberately the ones that already existed, plus two the Exchange path introduced:
 * a source can now say that the release you are *on* has been withdrawn, and it can name a version
 * without being able to say anything about the wire format.
 */
data class CatalogVersionStatusView(
    val state: String,
    val installedVersion: String?,
    val availableVersion: String?,
    val sourceSchemaVersion: Int?,
    val currentSchemaVersion: Int?,
    val detail: String?,
    /** Whether applying an upgrade goes through the Exchange dialog rather than the manifest diff. */
    val fromExchange: Boolean,
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
        ): CatalogVersionStatusView {
            val installed = catalog.installedReleaseVersion
            val base = CatalogVersionStatusView(
                state = UNCHECKED,
                installedVersion = installed,
                availableVersion = null,
                sourceSchemaVersion = null,
                currentSchemaVersion = currentSchemaVersion,
                detail = null,
                fromExchange = fromExchange,
            )
            if (catalog.type != CatalogType.SUBSCRIBED) return base
            if (catalog.sourceUrl == null) return base.copy(state = ZIP_MANAGED)
            if (check == null) return base

            val sourceSchema = check.availableSchemaVersion
            val state = when {
                check.failure == CatalogUpstreamCheckFailure.SCHEMA_TOO_NEW -> SOURCE_AHEAD
                check.failure != null -> CHECK_FAILED
                check.installedAvailability == UpstreamAvailability.WITHDRAWN -> WITHDRAWN
                sourceSchema != null && sourceSchema < currentSchemaVersion -> NOT_IN_SYNC
                sourceSchema != null && sourceSchema > currentSchemaVersion -> SOURCE_AHEAD
                check.upgradeAvailable(installed) -> UPDATE_AVAILABLE
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
    // Pair() rather than `to`: inside this builder `to` is the model-binding infix, not Kotlin's.
    "versionStatus" to rows.associate { row ->
        Pair(
            row.catalog.id.value,
            CatalogVersionStatusView.of(
                row.catalog,
                upstream[row.catalog.id],
                CATALOG_SCHEMA_VERSION,
                fromExchange = ExchangeSourceUri.matches(row.catalog.sourceUrl),
            ),
        )
    }
    "exchangeInstallAvailable" to ResolveCatalogInstallAvailability(tenantKey).query()
}
