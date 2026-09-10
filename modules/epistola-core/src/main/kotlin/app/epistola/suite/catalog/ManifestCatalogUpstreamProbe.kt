// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.migrations.CatalogSchemaException
import app.epistola.suite.catalog.migrations.CatalogSchemaTooNewException
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException

/**
 * The probe for catalogs subscribed from a URL: fetches the manifest and nothing else.
 *
 * This is what `CheckCatalogUpgrade` has always done, moved behind the probe seam so that a
 * scheduled check can do it for a catalog nobody is looking at. Manifest-only by design — the
 * per-resource details are an order of magnitude more requests and answer a different question
 * (what changed), which is still `PreviewCatalogUpgrade`'s job.
 */
@Component
class ManifestCatalogUpstreamProbe(
    private val catalogClient: CatalogClient,
) : CatalogUpstreamProbe {

    override fun supports(catalog: Catalog): Boolean {
        val sourceUrl = catalog.sourceUrl ?: return false
        return sourceUrl.substringBefore(':') in MANIFEST_SCHEMES
    }

    override fun probe(catalog: Catalog): CatalogUpstreamState {
        val sourceUrl = catalog.sourceUrl
            ?: throw CatalogUpstreamCheckException(CatalogUpstreamCheckFailure.NOT_FOUND, "Catalog has no source")
        val migrated = try {
            catalogClient.fetchMigratedManifest(sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential?.value)
        } catch (e: CatalogSchemaTooNewException) {
            // A subscribed catalog is a mirror and is never migrated locally, so the remedy is to
            // upgrade this installation. Recorded rather than retried: it will not change on its own.
            throw CatalogUpstreamCheckException(CatalogUpstreamCheckFailure.SCHEMA_TOO_NEW, e.message, e)
        } catch (e: CatalogSchemaException) {
            throw CatalogUpstreamCheckException(CatalogUpstreamCheckFailure.PROTOCOL_ERROR, e.message, e)
        } catch (e: ResourceAccessException) {
            throw CatalogUpstreamCheckException(CatalogUpstreamCheckFailure.UNREACHABLE, e.message, e)
        } catch (e: HttpClientErrorException.NotFound) {
            throw CatalogUpstreamCheckException(CatalogUpstreamCheckFailure.NOT_FOUND, e.message, e)
        } catch (e: HttpClientErrorException.Unauthorized) {
            throw CatalogUpstreamCheckException(CatalogUpstreamCheckFailure.UNAUTHORIZED, e.message, e)
        } catch (e: HttpClientErrorException.Forbidden) {
            throw CatalogUpstreamCheckException(CatalogUpstreamCheckFailure.UNAUTHORIZED, e.message, e)
        }

        return CatalogUpstreamState(
            availableVersion = migrated.manifest.release.version,
            availableFingerprint = migrated.manifest.release.fingerprint,
            availableSchemaVersion = migrated.catalog.sourceVersion,
            // A manifest describes only what it currently publishes, so it can say nothing about
            // whether the release this catalog sits on is still offered.
            installedAvailability = null,
        )
    }

    private companion object {
        /** What `CatalogClient` can fetch. Anything else belongs to another probe. */
        val MANIFEST_SCHEMES = setOf("https", "http", "file", "classpath")
    }
}
