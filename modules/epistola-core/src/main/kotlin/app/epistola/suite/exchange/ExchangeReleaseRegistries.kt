// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.queries.ListCatalogReleases
import app.epistola.suite.catalog.registry.EXCHANGE_RELEASE_REGISTRY_NAME
import app.epistola.suite.catalog.registry.NamespaceNotGrantedException
import app.epistola.suite.catalog.registry.RegistryArchiveRejectedException
import app.epistola.suite.catalog.registry.RegistryReleaseNotFoundException
import app.epistola.suite.catalog.registry.ReleaseRegistry
import app.epistola.suite.catalog.registry.ReleaseRegistryConflictException
import app.epistola.suite.catalog.registry.ReleaseRegistryInstall
import app.epistola.suite.catalog.registry.ReleaseRegistryKind
import app.epistola.suite.catalog.registry.ReleaseRegistryListing
import app.epistola.suite.catalog.registry.ReleaseRegistryProvider
import app.epistola.suite.catalog.registry.ReleaseRegistryPublication
import app.epistola.suite.catalog.registry.ReleaseRegistryRelease
import app.epistola.suite.catalog.registry.ReleaseRegistrySummary
import app.epistola.suite.catalog.registry.releaseVersionsNewestFirst
import app.epistola.suite.catalog.sources.CatalogSourceUnavailableException
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * The Epistola Exchange as a release registry (contract ADR 0008, kind `exchange`), named
 * [EXCHANGE_RELEASE_REGISTRY_NAME]. A tenant has it when the deployment enables Exchange, the
 * tenant is connected, and it may install from or publish to it. Installing is
 * [InstallExchangeCatalog]; publishing is [PublishCurrentCatalogRelease], which queues the release
 * in the publication outbox, so it answers `queued`.
 */
@Component
@Order(1)
class ExchangeReleaseRegistries(
    private val availability: ExchangeAvailability,
    private val credentials: ExchangeCredentialService,
    private val client: ExchangeClient,
    private val installer: ExchangeCatalogInstaller,
) : ReleaseRegistryProvider {

    override fun registries(tenantKey: TenantKey): List<ReleaseRegistry> {
        if (!availability.deploymentEnabled) return emptyList()
        val connection = credentials.activeConnection(tenantKey) ?: return emptyList()
        if (!availability.isInstallAvailable(tenantKey) && !availability.isAvailable(tenantKey)) return emptyList()
        return listOf(ExchangeReleaseRegistry(ReleaseRegistrySummary(EXCHANGE_RELEASE_REGISTRY_NAME, ReleaseRegistryKind.EXCHANGE, connection.baseUrl)))
    }

    private inner class ExchangeReleaseRegistry(override val summary: ReleaseRegistrySummary) : ReleaseRegistry {

        override fun list(tenantKey: TenantKey, namespace: String, catalogKey: String): ReleaseRegistryListing = refusals {
            val connection = credentials.activeConnection(tenantKey)
                ?: throw CatalogSourceUnavailableException("This tenant is not connected to Epistola Exchange. Connect it on the Exchange page first.")
            val token = credentials.accessToken(connection)
                ?: throw CatalogSourceUnavailableException("Epistola Exchange would not issue an access token for this tenant. Reconnect it on the Exchange page.")
            val offered = translate { client.releases(connection.baseUrl, token, namespace, catalogKey) }.filter { it.installable }.associateBy { it.version }
            val conflict = installer.localConflict(tenantKey, catalogKey, ExchangeSourceUri.of(namespace, catalogKey))
            val here = if (conflict == null) ListCatalogReleases(tenantKey, CatalogKey.of(catalogKey)).query().map { it.version }.toSet() else emptySet()
            ReleaseRegistryListing(
                registry = name,
                kind = ReleaseRegistryKind.EXCHANGE,
                namespace = namespace,
                catalogKey = catalogKey,
                releases = releaseVersionsNewestFirst(offered.keys).map { version ->
                    val release = offered.getValue(version)
                    ReleaseRegistryRelease(version = version, fingerprint = release.fingerprint, sizeBytes = release.sizeBytes, present = version in here)
                },
                conflict = conflict,
            )
        }

        override fun install(tenantKey: TenantKey, namespace: String, catalogKey: String, version: String): ReleaseRegistryInstall = refusals {
            val installed = translate { InstallExchangeCatalog(tenantKey, namespace, catalogKey, version).execute() }
            ReleaseRegistryInstall(name, installed.imported, installed.aborted)
        }

        override fun publish(tenantKey: TenantKey, catalogKey: CatalogKey, version: String): ReleaseRegistryPublication = refusals {
            val outcome = PublishCurrentCatalogRelease(tenantKey, catalogKey, version).execute()
            ReleaseRegistryPublication(
                registry = name,
                kind = ReleaseRegistryKind.EXCHANGE,
                version = outcome.version,
                status = when (outcome.status) {
                    ExchangePublicationOutcome.Status.ALREADY_PUBLISHED -> ReleaseRegistryPublication.Status.ALREADY_PUBLISHED
                    ExchangePublicationOutcome.Status.QUEUED, ExchangePublicationOutcome.Status.ALREADY_QUEUED -> ReleaseRegistryPublication.Status.QUEUED
                },
            )
        }

        /** The Exchange commands' refusals, as release registry refusals; any other passes as it is. */
        private fun <T> refusals(block: () -> T): T = try {
            block()
        } catch (e: ValidationException) {
            throw when (e.code) {
                ValidationCode.EXCHANGE_NAMESPACE_UNAVAILABLE -> NamespaceNotGrantedException(e.message)

                ValidationCode.EXCHANGE_RELEASE_UNAVAILABLE, ValidationCode.PUBLICATION_NO_RELEASE -> RegistryReleaseNotFoundException(e.message)

                ValidationCode.EXCHANGE_ARCHIVE_REJECTED -> RegistryArchiveRejectedException(e.message)

                ValidationCode.EXCHANGE_CATALOG_KEY_TAKEN,
                ValidationCode.EXCHANGE_INSTALL_UNAVAILABLE,
                ValidationCode.PUBLICATION_UNAVAILABLE,
                ValidationCode.PUBLICATION_FORBIDDEN_BY_POLICY,
                ValidationCode.PUBLICATION_REJECTED,
                ValidationCode.PUBLICATION_ARCHIVE_MISSING,
                -> ReleaseRegistryConflictException(e.message)

                else -> e
            }
        }
    }
}
