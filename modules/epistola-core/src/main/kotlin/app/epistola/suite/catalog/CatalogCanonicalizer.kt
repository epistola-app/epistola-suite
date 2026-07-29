// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.archive.CatalogArchive
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.DependencyRef
import app.epistola.catalog.protocol.PublisherInfo
import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.ResourceEntry
import app.epistola.catalog.validation.CatalogValidationPolicy
import app.epistola.catalog.validation.CatalogValidator
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import app.epistola.catalog.canonical.CatalogCanonicalizer as PortableCatalogCanonicalizer

/**
 * Suite adapter for the portable catalog canonicalizer.
 *
 * The Suite keeps its Jackson 3 mapper at the persistence and HTTP boundaries,
 * while canonicalization itself is owned by epistola-catalog.
 */
class CatalogCanonicalizer(private val objectMapper: ObjectMapper) {
    fun fingerprintFromSerializedDetails(
        catalog: CatalogInfo,
        serializedDetails: Map<String, ByteArray>,
        dependencies: List<DependencyRef>?,
        assetBytes: (contentUrl: String) -> ByteArray?,
    ): String = archive(catalog, serializedDetails, dependencies, assetBytes = assetBytes).use {
        PortableCatalogCanonicalizer.currentFingerprint(it).value
    }

    fun fingerprint(content: CatalogContent): String = archive(
        content.catalog,
        content.resourceDetails.mapValues { (_, detail) -> objectMapper.writeValueAsBytes(detail) },
        content.dependencies,
        content.resourceEntries,
        includeSerializedDetails = false,
    ) { contentUrl ->
        content.assetContents[contentUrl.removePrefix("./resources/asset/")]
    }.use { PortableCatalogCanonicalizer.currentFingerprint(it).value }

    fun fingerprint(
        catalog: CatalogInfo,
        resourceDetails: Map<String, ResourceDetail>,
        dependencies: List<DependencyRef>?,
        assetBytes: (contentUrl: String) -> ByteArray?,
    ): String = fingerprintFromSerializedDetails(
        catalog,
        resourceDetails.mapValues { (_, detail) -> objectMapper.writeValueAsBytes(detail) },
        dependencies,
        assetBytes,
    )

    fun matchesFingerprint(content: CatalogContent, expected: String): Boolean = archive(
        content.catalog,
        content.resourceDetails.mapValues { (_, detail) -> objectMapper.writeValueAsBytes(detail) },
        content.dependencies,
        content.resourceEntries,
        includeSerializedDetails = false,
    ) { contentUrl ->
        content.assetContents[contentUrl.removePrefix("./resources/asset/")]
    }.use { PortableCatalogCanonicalizer.matchesFingerprint(it, expected) }

    fun requirePublishable(content: CatalogContent) {
        archive(
            content.catalog,
            content.resourceDetails.mapValues { (_, detail) -> objectMapper.writeValueAsBytes(detail) },
            content.dependencies,
            content.resourceEntries,
        ) { contentUrl ->
            content.assetContents[contentUrl.removePrefix("./resources/asset/")]
        }.use { archive ->
            val report = CatalogValidator.validate(archive, CatalogValidationPolicy(verifyFingerprint = false))
            require(report.valid) {
                report.findings.joinToString(
                    prefix = "Catalog contains non-publishable content: ",
                    separator = "; ",
                ) { "${it.path}: ${it.message}" }
            }
        }
    }

    fun perResourceFingerprintsFromSerializedDetails(
        serializedDetails: Map<String, ByteArray>,
        assetBytes: (contentUrl: String) -> ByteArray?,
    ): Map<String, String> = archive(
        PLACEHOLDER_CATALOG,
        serializedDetails,
        null,
        assetBytes = assetBytes,
    ).use(PortableCatalogCanonicalizer::currentPerResourceFingerprints)

    fun perResourceFingerprints(
        resourceDetails: Map<String, ResourceDetail>,
        assetBytes: (contentUrl: String) -> ByteArray?,
    ): Map<String, String> = perResourceFingerprintsFromSerializedDetails(
        resourceDetails.mapValues { (_, detail) -> objectMapper.writeValueAsBytes(detail) },
        assetBytes,
    )

    fun fingerprintFromSource(
        catalogClient: CatalogClient,
        manifestUrl: String,
        authType: AuthType,
        credential: String?,
    ): String {
        val manifest = catalogClient.fetchManifest(manifestUrl, authType, credential)
        val serializedDetails = manifest.resources.associate { entry ->
            "${entry.type}/${entry.slug}" to
                catalogClient.fetchBinaryContent(entry.detailUrl, manifestUrl, authType, credential)
        }
        return fingerprintFromSerializedDetails(
            manifest.catalog,
            serializedDetails,
            manifest.dependencies,
        ) { contentUrl ->
            catalogClient.fetchBinaryContent(contentUrl, manifestUrl, authType, credential)
        }
    }

    private fun archive(
        catalog: CatalogInfo,
        serializedDetails: Map<String, ByteArray>,
        dependencies: List<DependencyRef>?,
        resourceEntries: List<ResourceEntry> = emptyList(),
        includeSerializedDetails: Boolean = true,
        assetBytes: (contentUrl: String) -> ByteArray?,
    ): CatalogArchive {
        val details = serializedDetails.mapValues { (_, bytes) ->
            objectMapper.readValue(bytes, ResourceDetail::class.java)
        }
        val detailContent = if (includeSerializedDetails) {
            serializedDetails.mapKeys { (key, _) -> "resources/$key.json" }
        } else {
            emptyMap()
        }
        val assets = details.values
            .mapNotNull { detail -> detail.resource.contentUrlOrNull() }
            .associate { contentUrl ->
                contentUrl.removePrefix("./") to assetBytes(contentUrl)
            }
            .filterValues { it != null }
            .mapValues { (_, bytes) -> requireNotNull(bytes) }
        val content = detailContent + assets
        return CatalogArchive(
            manifest = CatalogManifest(
                schemaVersion = CATALOG_SCHEMA_VERSION,
                catalog = catalog,
                publisher = PublisherInfo("Epistola"),
                release = ReleaseInfo("0.0.0"),
                resources = resourceEntries,
                dependencies = dependencies,
            ),
            resourceDetails = details,
            paths = content.keys,
            content = { path -> ByteArrayInputStream(requireNotNull(content[path]) { "Missing catalog content: $path" }) },
        )
    }

    private fun Any.contentUrlOrNull(): String? {
        val tree = objectMapper.valueToTree<tools.jackson.databind.JsonNode>(this)
        return tree.get("contentUrl")?.asString()
    }

    companion object {
        private val PLACEHOLDER_CATALOG = CatalogInfo("fingerprints", "Fingerprints")
    }
}
