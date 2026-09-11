// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogUpstreamCheckException
import app.epistola.suite.catalog.CatalogUpstreamCheckFailure
import app.epistola.suite.catalog.CatalogUpstreamProbe
import app.epistola.suite.catalog.CatalogUpstreamState
import app.epistola.suite.catalog.UpstreamAvailability
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientException

/**
 * Answers "is there a newer release?" for catalogs installed from Epistola Exchange.
 *
 * This is the whole of the Exchange integration's contribution to the catalog domain: an
 * implementation of an interface that domain owns, contributed as a component. The dependency runs
 * `exchange` to `catalog`, which is the allowed direction and what keeps
 * `CatalogExchangeIndependenceTest` green.
 *
 * One call in the steady state. `latestVersion` on the catalog summary is what Exchange computes as
 * the newest release anyone may install, so blocked and withdrawn ones are already excluded before
 * we see it, and there is no need to page through the release list to find out nothing changed.
 * The release list is fetched only when the answer differs — to find the newest release actually
 * available to *this* connection, which is not always the one Exchange advertises.
 */
@Component
class ExchangeCatalogUpstreamProbe(
    private val credentials: ExchangeCredentialService,
    private val client: ExchangeClient,
    private val availability: ExchangeAvailability,
) : CatalogUpstreamProbe {

    override fun supports(catalog: Catalog): Boolean = ExchangeSourceUri.matches(catalog.sourceUrl)

    override fun probe(catalog: Catalog): CatalogUpstreamState {
        if (!availability.deploymentEnabled) {
            throw CatalogUpstreamCheckException(CatalogUpstreamCheckFailure.PAUSED, "Exchange is not enabled on this installation")
        }
        val coordinates = ExchangeSourceUri.parse(catalog.sourceUrl)
            ?: throw CatalogUpstreamCheckException(
                CatalogUpstreamCheckFailure.PROTOCOL_ERROR,
                "'${catalog.sourceUrl}' is not a usable Exchange address",
            )
        val connection = credentials.activeConnection(catalog.tenantKey)
            ?: throw CatalogUpstreamCheckException(
                CatalogUpstreamCheckFailure.UNAUTHORIZED,
                "This tenant is not connected to Exchange",
            )
        val token = credentials.accessToken(connection)
            ?: throw CatalogUpstreamCheckException(
                CatalogUpstreamCheckFailure.UNAUTHORIZED,
                "Exchange would not issue an access token for this tenant",
            )

        return try {
            read(connection.baseUrl, token, coordinates, catalog.installedReleaseVersion)
        } catch (e: HttpClientErrorException.NotFound) {
            // Either the catalog is gone or this connection may no longer see it. Exchange answers
            // both the same way on purpose, and so must this: a 404 is not evidence of which.
            throw CatalogUpstreamCheckException(CatalogUpstreamCheckFailure.NOT_FOUND, e.message, e)
        } catch (e: HttpClientErrorException.Unauthorized) {
            throw CatalogUpstreamCheckException(CatalogUpstreamCheckFailure.UNAUTHORIZED, e.message, e)
        } catch (e: HttpClientErrorException.Forbidden) {
            throw CatalogUpstreamCheckException(CatalogUpstreamCheckFailure.UNAUTHORIZED, e.message, e)
        } catch (e: ResourceAccessException) {
            // Exchange being down is not this catalog's fault and never becomes its failure.
            throw CatalogUpstreamCheckException(CatalogUpstreamCheckFailure.UNREACHABLE, e.message, e)
        } catch (e: ExchangeProtocolException) {
            throw CatalogUpstreamCheckException(CatalogUpstreamCheckFailure.PROTOCOL_ERROR, e.message, e)
        } catch (e: RestClientException) {
            // Includes the case that matters most here: a vocabulary Exchange has extended, which
            // the generated client binds to a closed enum and fails the whole page over.
            throw CatalogUpstreamCheckException(CatalogUpstreamCheckFailure.PROTOCOL_ERROR, e.message, e)
        }
    }

    private fun read(
        baseUrl: String,
        token: String,
        coordinates: ExchangeCatalogCoordinates,
        installedVersion: String?,
    ): CatalogUpstreamState {
        val summary = client.catalog(baseUrl, token, coordinates.namespace, coordinates.catalogKey)
        val advertised = summary.latestVersion
        if (advertised != null && advertised == installedVersion) {
            // Nothing has moved, which is the common case and the one worth keeping to one request.
            return CatalogUpstreamState(availableVersion = advertised)
        }

        val releases = client.releases(baseUrl, token, coordinates.namespace, coordinates.catalogKey)
        val newest = releases.firstOrNull { it.installable }
        val installed = installedVersion?.let { version -> releases.firstOrNull { it.version == version } }

        return CatalogUpstreamState(
            availableVersion = newest?.version ?: advertised ?: installedVersion
                ?: throw CatalogUpstreamCheckException(
                    CatalogUpstreamCheckFailure.NOT_FOUND,
                    "Exchange offers no installable release of $coordinates",
                ),
            availableFingerprint = newest?.fingerprint,
            availablePublishedAt = newest?.publishedAt,
            // Absent from the list at all is treated as withdrawn: for a consumer the two are the
            // same fact — the bytes it is running are no longer offered — and Exchange stops
            // returning a release to everyone except the installation that publishes it.
            installedAvailability = when {
                installedVersion == null -> null
                installed == null -> UpstreamAvailability.WITHDRAWN
                else -> runCatching { UpstreamAvailability.valueOf(installed.availability) }
                    .getOrDefault(UpstreamAvailability.AVAILABLE)
            },
        )
    }
}
