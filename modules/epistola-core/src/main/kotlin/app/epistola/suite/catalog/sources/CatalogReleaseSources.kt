// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.sources

import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ImageResource
import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogArchiveBuilder
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZipResult
import app.epistola.suite.catalog.commands.publishedContent
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Where a subscribed catalog's releases come from (contract `listAvailableCatalogReleases`,
 * `installCatalogRelease`): the releases it offers, and each one's archive. Installing one installs
 * it beside the catalog's other releases, as importing its archive does.
 *
 * Implemented by each kind of source the catalog domain knows of: a manifest URL here, Epistola
 * Exchange in its own package, which this one must not reference.
 */
interface CatalogReleaseSource {
    fun supports(catalog: Catalog): Boolean

    /** The releases the source offers, by number. @throws CatalogSourceUnavailableException */
    fun offered(catalog: Catalog): List<String>

    /** The archive of release [version]. @throws CatalogSourceUnavailableException */
    fun archive(catalog: Catalog, version: String): ByteArray

    /** Installs release [version], beside the catalog's others; an older wire schema is migrated, as nobody is asked. */
    fun install(catalog: Catalog, version: String): ImportCatalogZipResult = ImportCatalogZip(catalog.tenantKey, archive(catalog, version), confirmMigration = true).execute()

    /**
     * Installs release [version] of the catalog named [registry]/[namespace]/[catalogKey], which the
     * tenant lacks: a release another one pins. False when this source does not serve that registry.
     */
    fun installByName(tenantKey: TenantKey, registry: String, namespace: String, catalogKey: String, version: String): Boolean = false
}

/**
 * The source could not be asked (contract problem type `catalog-source-unavailable`): it could not
 * be reached or did not answer in time ([badGateway] false: 503), or answered with an error or
 * something that is not a valid answer ([badGateway] true: 502).
 */
class CatalogSourceUnavailableException(
    message: String,
    val badGateway: Boolean = false,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** The sources a subscribed catalog may have, asked in order. */
@Component
class CatalogReleaseSources(private val sources: List<CatalogReleaseSource>) {
    /** The source of [catalog], or null when it has none (an authored catalog, or one imported from an archive). */
    fun of(catalog: Catalog): CatalogReleaseSource? = sources.firstOrNull { it.supports(catalog) }

    /** Installs a release a pin names from the first source serving its registry; false when none does. */
    fun installByName(tenantKey: TenantKey, registry: String, namespace: String, catalogKey: String, version: String): Boolean = sources.any { it.installByName(tenantKey, registry, namespace, catalogKey, version) }
}

/**
 * A catalog subscribed from a manifest URL (any address [CatalogClient] reads: `https:`, a bundled
 * `classpath:`, a local `file:`): it offers the one release its manifest describes now, assembled into
 * an archive from the files it names. Asked last, after the sources that claim a scheme of their own.
 */
@Component
@Order
class ManifestUrlReleaseSource(
    private val client: CatalogClient,
    private val objectMapper: ObjectMapper,
    private val archiveBuilder: CatalogArchiveBuilder,
) : CatalogReleaseSource {
    override fun supports(catalog: Catalog): Boolean = catalog.sourceUrl != null

    override fun offered(catalog: Catalog): List<String> = listOf(fetch { manifest(catalog) }.release.version)

    override fun archive(catalog: Catalog, version: String): ByteArray = fetch {
        val url = requireNotNull(catalog.sourceUrl)
        val auth = catalog.sourceAuthType
        val credential = catalog.sourceAuthCredential?.value
        val manifest = manifest(catalog)
        check(manifest.release.version == version) { "${catalog.id.value}'s source offers ${manifest.release.version}, not $version" }
        val bound: Map<String, CatalogResource> = manifest.resources.associate { entry ->
            "${entry.type}/${entry.key}" to objectMapper.readValue(client.fetchBinaryContent(entry.detailUrl, url, auth, credential), ResourceDetail::class.java).resource
        }
        val binaries = bound.values.flatMap { resource ->
            when (resource) {
                is ImageResource -> listOf(resource.contentPath())
                is FontResource -> resource.variants.map { it.contentPath() }
                else -> emptyList()
            }
        }.distinct().associateWith { path -> client.fetchBinaryContent(path, url, auth, credential) }
        val content = publishedContent(manifest, bound, binaries)
        archiveBuilder.build(content, ReleaseInfo(version = manifest.release.version, releasedAt = manifest.release.releasedAt, fingerprint = manifest.release.fingerprint))
    }

    private fun manifest(catalog: Catalog) = client.fetchManifest(requireNotNull(catalog.sourceUrl), catalog.sourceAuthType, catalog.sourceAuthCredential?.value)

    private fun <T> fetch(block: () -> T): T = try {
        block()
    } catch (e: CatalogSourceUnavailableException) {
        throw e
    } catch (e: org.springframework.web.client.ResourceAccessException) {
        throw CatalogSourceUnavailableException("The catalog's source could not be reached: ${e.message}", cause = e)
    } catch (e: org.springframework.web.client.RestClientException) {
        throw CatalogSourceUnavailableException("The catalog's source answered with an error: ${e.message}", badGateway = true, cause = e)
    } catch (e: tools.jackson.core.JacksonException) {
        throw CatalogSourceUnavailableException("The catalog's source answered with something that is not a catalog: ${e.message}", badGateway = true, cause = e)
    }
}
