// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component

/**
 * Where a catalog installed from Epistola Exchange can be read about on Exchange itself.
 *
 * Returns the catalog's **root** rather than a finished link, the same shape and for the same reason
 * as [CatalogPublicationState.exchangeOrganizationUrl]: callers append the part they need, and a
 * caller with several links to build (one per resource on a browse page) does not ask once per row.
 *
 * Null whenever the link would be a guess — the catalog came from somewhere other than Exchange, the
 * source URI is malformed, or this tenant has no connection to say which Exchange deployment is
 * meant. A page renders the plain text it rendered before rather than a link into nowhere.
 *
 * Permission is [Permission.CATALOG_VIEW], not `TENANT_SETTINGS` like the other connection reads:
 * nothing here is connection state. The namespace and key come from `catalogs.source_url`, which the
 * same page already shows, and the base URL is the deployment's public address.
 */
data class GetExchangeCatalogLink(
    override val tenantKey: TenantKey,
    /** A catalog's `source_url`. Anything that is not an `exchange:` URI yields null. */
    val sourceUrl: String?,
) : Query<String?>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class GetExchangeCatalogLinkHandler(
    private val credentials: ExchangeCredentialService,
) : QueryHandler<GetExchangeCatalogLink, String?> {
    override fun handle(query: GetExchangeCatalogLink): String? {
        val coordinates = ExchangeSourceUri.parse(query.sourceUrl) ?: return null
        // The connection rather than an active session: this is a link for a person to follow in
        // their own browser, which carries their own credentials. A tenant whose connection has
        // lapsed still installed from somewhere, and the page it came from has not moved.
        val baseUrl = credentials.connection(query.tenantKey)?.baseUrl ?: return null
        return "${baseUrl.trimEnd('/')}/catalogs/${coordinates.namespace}/${coordinates.catalogKey}"
    }
}
