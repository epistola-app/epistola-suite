// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.CatalogSizeLimits
import app.epistola.suite.catalog.commands.ImportCatalogZipResult
import app.epistola.suite.catalog.sources.CatalogReleaseSource
import app.epistola.suite.catalog.sources.CatalogSourceUnavailableException
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.validation.ValidationException
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

/**
 * A catalog installed from Epistola Exchange (`exchange:<namespace>/<key>`): it offers the releases
 * Exchange lists as installable, each downloaded as its archive, and installs through
 * [ExchangeCatalogInstaller], which also installs a release another one pins from its registry.
 */
@Component
@Order(0)
class ExchangeCatalogReleaseSource(
    private val credentials: ExchangeCredentialService,
    private val client: ExchangeClient,
    private val installer: ExchangeCatalogInstaller,
    private val sizeLimits: CatalogSizeLimits,
    private val identity: CatalogIdentityProperties,
) : CatalogReleaseSource {

    override fun supports(catalog: Catalog): Boolean = ExchangeSourceUri.matches(catalog.sourceUrl)

    override fun offered(catalog: Catalog): List<String> = ask(catalog) { baseUrl, token, namespace, key ->
        client.releases(baseUrl, token, namespace, key).filter { it.installable }.map { it.version }
    }

    override fun archive(catalog: Catalog, version: String): ByteArray = ask(catalog) { baseUrl, token, namespace, key ->
        client.downloadArchive(baseUrl, token, namespace, key, version, sizeLimits.maxZipSize.toBytes())
    }

    override fun install(catalog: Catalog, version: String): ImportCatalogZipResult {
        val coordinates = requireNotNull(ExchangeSourceUri.parse(catalog.sourceUrl)) { "'${catalog.sourceUrl}' is not an Exchange address" }
        return translate { installer.install(catalog.tenantKey, coordinates.namespace, coordinates.catalogKey, version).imported }
    }

    override fun installByName(tenantKey: TenantKey, registry: String, namespace: String, catalogKey: String, version: String): Boolean {
        if (registry != identity.defaultRegistry) return false
        translate { installer.install(tenantKey, namespace, catalogKey, version) }
        return true
    }

    private fun <T> ask(catalog: Catalog, call: (baseUrl: String, token: String, namespace: String, catalogKey: String) -> T): T {
        val coordinates = requireNotNull(ExchangeSourceUri.parse(catalog.sourceUrl)) { "'${catalog.sourceUrl}' is not an Exchange address" }
        val connection = credentials.activeConnection(catalog.tenantKey)
            ?: throw CatalogSourceUnavailableException("This tenant is not connected to Epistola Exchange. Connect it on the Exchange page first.")
        val token = credentials.accessToken(connection)
            ?: throw CatalogSourceUnavailableException("Epistola Exchange would not issue an access token for this tenant. Reconnect it on the Exchange page.")
        return translate { call(connection.baseUrl, token, coordinates.namespace, coordinates.catalogKey) }
    }

    /** Exchange's failures, as the contract names them: unreachable 503, a bad answer 502. */
    private fun <T> translate(block: () -> T): T = try {
        block()
    } catch (e: ValidationException) {
        throw e
    } catch (e: ResourceAccessException) {
        throw CatalogSourceUnavailableException("Epistola Exchange could not be reached: ${e.message}", cause = e)
    } catch (e: RestClientResponseException) {
        val retryAfter = e.responseHeaders?.getFirst("Retry-After")?.toLongOrNull()
        throw CatalogSourceUnavailableException("Epistola Exchange answered ${e.statusCode.value()}: ${e.message}", badGateway = e.statusCode.value() != 503, retryAfterSeconds = retryAfter, cause = e)
    } catch (e: RestClientException) {
        throw CatalogSourceUnavailableException("Epistola Exchange answered with something that is not a valid answer: ${e.message}", badGateway = true, cause = e)
    }
}
