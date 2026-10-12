// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import app.epistola.suite.exchange.ExchangeProperties
import app.epistola.suite.productdomain.ProductDiscovery
import app.epistola.suite.productdomain.ProductDiscoveryClient
import app.epistola.suite.productdomain.ProductDomainProperties
import org.springframework.stereotype.Component

/**
 * The discovery request (ADR 0030) as the "Shared with Epistola" page shows it, next to the checks
 * that use it. It is sent only when an integration needs an endpoint it does not have configured.
 */
@Component
class ProductDiscoverySummary(
    private val discovery: ProductDiscovery,
    private val domain: ProductDomainProperties,
    private val versionCheck: VersionCheckProperties,
    private val installationCheck: InstallationCheckProperties,
    private val sharing: SharingProperties,
    private val exchange: ExchangeProperties,
) {
    /**
     * Whether something here relies on discovery. The support hub, which this module cannot see,
     * shows up through the cached status once it has used it.
     */
    fun inUse(): Boolean = inUse(versionCheck, installationCheck, sharing, exchange, fetchedBefore = discovery.status()?.lastAttemptAt != null)

    fun summary(): CheckSummary {
        val status = discovery.status()
        val enabled = inUse()
        return CheckSummary(
            enabled = enabled,
            request = if (enabled) OutgoingRequest(domain.discoveryUrl, ProductDiscoveryClient.HEADERS) else null,
            lastRunAt = status?.lastAttemptAt,
            lastResult = status?.let { it.lastError ?: "OK" },
            lastRequest = null,
        )
    }

    /** Fetches the document now, when anything relies on it. */
    fun refreshIfInUse() {
        if (inUse()) discovery.refresh()
    }

    companion object {
        fun inUse(
            versionCheck: VersionCheckProperties,
            installationCheck: InstallationCheckProperties,
            sharing: SharingProperties,
            exchange: ExchangeProperties,
            fetchedBefore: Boolean,
        ): Boolean = (versionCheck.enabled && versionCheck.endpoint.isBlank()) ||
            (sharing.installationId && installationCheck.endpoint.isBlank()) ||
            (exchange.enabled && exchange.configuredEndpoint == null) ||
            fetchedBefore
    }
}
