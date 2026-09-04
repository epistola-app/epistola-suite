// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers.nav

import app.epistola.suite.catalog.CatalogUpstreamCheck
import app.epistola.suite.catalog.UpstreamAvailability
import app.epistola.suite.catalog.queries.GetCatalogUpstreamStates
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.home.HomeNotice
import app.epistola.suite.htmx.home.HomeNoticeContributor
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import org.springframework.stereotype.Component

/**
 * Tells someone landing on the tenant home that a catalog they installed has moved on.
 *
 * The nav count says *that* something is waiting from any page; this says *what*, where there is
 * room to name it. Both read the same recorded state, so neither costs a remote call.
 *
 * Withdrawn releases are called out separately and first. An available update is an opportunity; a
 * withdrawn release is a fact about content already in use, and the two should not be averaged into
 * one number.
 */
@Component
class CatalogUpdateHomeNoticeContributor : HomeNoticeContributor {

    override fun notices(context: UiRequestContext): List<HomeNotice> {
        if (!context.hasPermission(Permission.CATALOG_VIEW)) return emptyList()

        val installed = ListCatalogs(context.tenantKey).query().associateBy { it.id }
        val checks = GetCatalogUpstreamStates(context.tenantKey).query()

        val updates = checks.entries.mapNotNull { (key, check) ->
            val catalog = installed[key] ?: return@mapNotNull null
            val name = catalog.name
            when {
                check.installedAvailability == UpstreamAvailability.WITHDRAWN ->
                    CatalogUpdateNotice(name, check, withdrawn = true)
                check.upgradeAvailable(catalog.installedReleaseVersion) ->
                    CatalogUpdateNotice(name, check, withdrawn = false)
                else -> null
            }
        }.sortedByDescending { it.withdrawn }

        if (updates.isEmpty()) return emptyList()
        return listOf(
            HomeNotice(
                template = "catalogs/home-notice",
                fragment = "banner",
                data = CatalogUpdatesNoticeData(context.tenantKey.value, updates),
                // After the version-check banner: an out-of-support Epistola outranks a catalog.
                order = 20,
            ),
        )
    }
}

/**
 * What the banner says, resolved here rather than assembled in the template.
 *
 * The wording is a rule ("one catalog" vs "three catalogs", name the version when there is one),
 * and rules in a Thymeleaf expression are rules nothing can test.
 */
data class CatalogUpdatesNoticeData(
    val tenantId: String,
    val updates: List<CatalogUpdateNotice>,
) {
    val withdrawn: List<CatalogUpdateNotice> get() = updates.filter { it.withdrawn }
    val available: List<CatalogUpdateNotice> get() = updates.filterNot { it.withdrawn }

    val withdrawnHeadline: String get() = if (withdrawn.size == 1) {
        "A catalog release you are using has been withdrawn"
    } else {
        "${withdrawn.size} catalog releases you are using have been withdrawn"
    }

    val availableHeadline: String get() = if (available.size == 1) {
        "1 catalog has an update available"
    } else {
        "${available.size} catalogs have updates available"
    }

    val withdrawnSummary: String get() = withdrawn.joinToString(", ") { it.catalogName }

    val availableSummary: String get() = available.joinToString(", ") { it.summary }
}

data class CatalogUpdateNotice(
    val catalogName: String,
    val check: CatalogUpstreamCheck,
    val withdrawn: Boolean,
) {
    val availableVersion: String? get() = check.availableVersion

    /** "Invoices v1.1.0", or just the name when the source did not name a version. */
    val summary: String get() = availableVersion?.let { "$catalogName v$it" } ?: catalogName
}
