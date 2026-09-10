// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.SemVer
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClientException

/**
 * Browsing Epistola Exchange from inside Suite.
 *
 * These are queries that make a remote call, which `CheckCatalogUpgrade` already establishes as
 * acceptable: `SpringMediator` opens a transaction for commands, not queries, so nothing here holds
 * a database connection while waiting on somebody else's server.
 *
 * Neither throws when the tenant is not connected. Browsing is the first thing anyone tries, and
 * "you are not connected yet" is a page to render with a way forward, not an error.
 */
data class SearchExchangeCatalogs(
    override val tenantKey: TenantKey,
    val query: String? = null,
    val cursor: String? = null,
) : Query<ExchangeBrowseResult>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/**
 * What browsing produced, including the reasons there is nothing to show.
 *
 * A single shape rather than an exception per case, because every one of them is something the page
 * has to say to a reader: the feature is off, nobody has connected this tenant, or Exchange itself
 * could not be reached.
 */
data class ExchangeBrowseResult(
    val catalogs: List<ExchangeCatalogSummary> = emptyList(),
    val nextCursor: String? = null,
    val unavailable: ExchangeBrowseUnavailable? = null,
) {
    val available: Boolean get() = unavailable == null
}

enum class ExchangeBrowseUnavailable(val message: String) {
    FEATURE_OFF("Installing catalogs from Epistola Exchange is not enabled for this tenant."),
    NOT_CONNECTED("Connect this tenant to Epistola Exchange to browse and install catalogs."),
    UNREACHABLE("Epistola Exchange could not be reached. Try again shortly."),
}

@Component
class SearchExchangeCatalogsHandler(
    private val browser: ExchangeCatalogBrowser,
) : QueryHandler<SearchExchangeCatalogs, ExchangeBrowseResult> {
    override fun handle(query: SearchExchangeCatalogs): ExchangeBrowseResult = browser.search(query.tenantKey, query.query, query.cursor)
}

data class GetExchangeCatalogDetail(
    override val tenantKey: TenantKey,
    val namespace: String,
    val catalogKey: String,
) : Query<ExchangeCatalogDetail?>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/**
 * One catalog on Exchange and the releases of it this tenant may install.
 *
 * Releases are ordered by SemVer rather than left as Exchange returned them: Exchange orders by
 * publication, which normally agrees with version order but is not required to, and a version
 * picker that disagrees with itself is worse than one that is merely a little slower to build.
 */
data class ExchangeCatalogDetail(
    val catalog: ExchangeCatalogSummary,
    val releases: List<ExchangeCatalogRelease>,
) {
    val installable: List<ExchangeCatalogRelease> get() = releases.filter { it.installable }
    val newest: ExchangeCatalogRelease? get() = installable.firstOrNull()
}

@Component
class GetExchangeCatalogDetailHandler(
    private val browser: ExchangeCatalogBrowser,
) : QueryHandler<GetExchangeCatalogDetail, ExchangeCatalogDetail?> {
    override fun handle(query: GetExchangeCatalogDetail): ExchangeCatalogDetail? = browser.detail(query.tenantKey, query.namespace, query.catalogKey)
}

/** Shared gate-and-call plumbing for the browse queries, so neither re-derives the conditions. */
@Component
class ExchangeCatalogBrowser(
    private val availability: ExchangeAvailability,
    private val credentials: ExchangeCredentialService,
    private val client: ExchangeClient,
) {
    fun search(tenantKey: TenantKey, query: String?, cursor: String?): ExchangeBrowseResult {
        val session = session(tenantKey) ?: return unavailableFor(tenantKey)
        return try {
            val page = client.searchCatalogs(session.baseUrl, session.token, query, cursor, SEARCH_PAGE_SIZE)
            ExchangeBrowseResult(page.items, page.nextCursor)
        } catch (e: RestClientException) {
            log.warn("Browsing Exchange failed for tenant {}: {}", tenantKey.value, e.message)
            ExchangeBrowseResult(unavailable = ExchangeBrowseUnavailable.UNREACHABLE)
        }
    }

    fun detail(tenantKey: TenantKey, namespace: String, catalogKey: String): ExchangeCatalogDetail? {
        val session = session(tenantKey) ?: return null
        return try {
            val catalog = client.catalog(session.baseUrl, session.token, namespace, catalogKey)
            val releases = client.releases(session.baseUrl, session.token, namespace, catalogKey)
                .sortedWith(
                    compareByDescending<ExchangeCatalogRelease> { SemVer.parseOrNull(it.version) }
                        .thenByDescending { it.version },
                )
            ExchangeCatalogDetail(catalog, releases)
        } catch (e: HttpClientErrorException.NotFound) {
            null
        }
    }

    private fun session(tenantKey: TenantKey): ExchangeSession? {
        if (!availability.isInstallAvailable(tenantKey)) return null
        val connection = credentials.activeConnection(tenantKey) ?: return null
        val token = credentials.accessToken(connection) ?: return null
        return ExchangeSession(connection.baseUrl, token)
    }

    private fun unavailableFor(tenantKey: TenantKey): ExchangeBrowseResult = ExchangeBrowseResult(
        unavailable = if (!availability.isInstallAvailable(tenantKey)) {
            ExchangeBrowseUnavailable.FEATURE_OFF
        } else {
            ExchangeBrowseUnavailable.NOT_CONNECTED
        },
    )

    private data class ExchangeSession(val baseUrl: String, val token: String)

    private companion object {
        val log = LoggerFactory.getLogger(ExchangeCatalogBrowser::class.java)
        const val SEARCH_PAGE_SIZE = 25
    }
}
