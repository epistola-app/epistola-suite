// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.registry

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogSizeLimits
import app.epistola.suite.catalog.CatalogUpstreamCheckException
import app.epistola.suite.catalog.CatalogUpstreamCheckFailure
import app.epistola.suite.catalog.CatalogUpstreamProbe
import app.epistola.suite.catalog.CatalogUpstreamState
import app.epistola.suite.catalog.sources.CatalogReleaseSource
import app.epistola.suite.catalog.sources.CatalogSourceUnavailableException
import app.epistola.suite.validation.ValidationException
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * A catalog installed from a registry (`oci:<registry>/<namespace>/<key>`), as a source the rest
 * of the suite asks without knowing it is one: the releases it offers (its release-number tags) and
 * each one's archive, verified against its digest, for `ListAvailableCatalogReleases` and
 * `InstallCatalogRelease`. Claimed before the manifest-URL source, which would otherwise try to
 * fetch the `oci:` address.
 */
@Component
@Order(1)
class RegistryCatalogReleaseSource(
    private val registries: CatalogRegistries,
    private val sizeLimits: CatalogSizeLimits,
) : CatalogReleaseSource {
    override fun provenance(catalog: Catalog): app.epistola.suite.catalog.releases.ReleaseProvenance = app.epistola.suite.catalog.releases.ReleaseProvenance(app.epistola.suite.catalog.releases.ReleaseChannel.REGISTRY, catalog.sourceUrl)

    override fun supports(catalog: Catalog): Boolean = RegistrySourceUri.matches(catalog.sourceUrl)

    override fun offered(catalog: Catalog): List<String> = at(catalog) { client, repository -> releaseVersionsNewestFirst(client.tags(repository)) }

    override fun archive(catalog: Catalog, version: String): ByteArray = at(catalog) { client, repository ->
        val pulled = client.manifest(repository, version)
            ?: throw CatalogSourceUnavailableException("The registry no longer has $repository:$version", badGateway = true)
        val layer = pulled.manifest.archive?.takeIf { pulled.manifest.isCatalogRelease }
            ?: throw CatalogSourceUnavailableException("$repository:$version is not a catalog release", badGateway = true)
        client.pullBlob(repository, layer, sizeLimits.maxZipSize.toBytes())
    }

    private fun <T> at(catalog: Catalog, call: (OciRegistryClient, String) -> T): T {
        val source = RegistrySourceUri.parse(catalog.sourceUrl)
            ?: throw CatalogSourceUnavailableException("'${catalog.sourceUrl}' is not a registry address", badGateway = true)
        val registry = try {
            registries.named(source.registry)
        } catch (e: ValidationException) {
            throw CatalogSourceUnavailableException("This installation has no registry called '${source.registry}' any more. ${e.message}", cause = e)
        }
        return call(registries.client(registry), registries.repository(registry, catalog.registry, source.namespace, source.catalogKey))
    }
}

/**
 * The scheduled "is there anything newer?" check for a catalog installed from a registry: the
 * highest release-number tag, with the fingerprint its manifest is labelled with (a label: the
 * install that follows recomputes it).
 */
@Component
class RegistryCatalogUpstreamProbe(
    private val registries: CatalogRegistries,
) : CatalogUpstreamProbe {
    override fun supports(catalog: Catalog): Boolean = RegistrySourceUri.matches(catalog.sourceUrl)

    override fun probe(catalog: Catalog): CatalogUpstreamState {
        val source = RegistrySourceUri.parse(catalog.sourceUrl)
            ?: throw CatalogUpstreamCheckException(CatalogUpstreamCheckFailure.NOT_FOUND, "'${catalog.sourceUrl}' is not a registry address")
        try {
            val registry = registries.named(source.registry)
            val client = registries.client(registry)
            val repository = registries.repository(registry, catalog.registry, source.namespace, source.catalogKey)
            val newest = releaseVersionsNewestFirst(client.tags(repository)).firstOrNull()
                ?: throw CatalogUpstreamCheckException(CatalogUpstreamCheckFailure.NOT_FOUND, "${registry.name} has no releases of $repository")
            val manifest = client.manifest(repository, newest)?.manifest
            return CatalogUpstreamState(
                availableVersion = newest,
                availableFingerprint = manifest?.fingerprint,
                upstreamAvailability = null,
            )
        } catch (e: ValidationException) {
            throw CatalogUpstreamCheckException(CatalogUpstreamCheckFailure.NOT_FOUND, e.message, e)
        } catch (e: CatalogSourceUnavailableException) {
            throw CatalogUpstreamCheckException(if (e.badGateway) CatalogUpstreamCheckFailure.PROTOCOL_ERROR else CatalogUpstreamCheckFailure.UNREACHABLE, e.message, e)
        }
    }
}
