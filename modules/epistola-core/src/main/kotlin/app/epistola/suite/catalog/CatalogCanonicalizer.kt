// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.archive.CatalogArchive
import app.epistola.catalog.canonical.QualifiedCatalogFingerprint
import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.DependencyRef
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ImageResource
import app.epistola.catalog.protocol.PublisherInfo
import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.ResourceEntry
import app.epistola.catalog.validation.CatalogValidationPolicy
import app.epistola.catalog.validation.CatalogValidationReport
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
    /**
     * The V5 fingerprint of a catalog given as its manifest's parts and serialized details. The
     * manifest's [resourceEntries] (each resource's type, key, name and description) and [publisher]
     * are part of what V5 covers, so a caller that has them passes them.
     */
    fun fingerprintFromSerializedDetails(
        catalog: CatalogInfo,
        serializedDetails: Map<String, ByteArray>,
        dependencies: List<DependencyRef>?,
        catalogDependencies: List<CatalogDependency>? = null,
        resourceEntries: List<ResourceEntry> = emptyList(),
        publisher: PublisherInfo = PublisherInfo("Epistola"),
        assetBytes: (contentUrl: String) -> ByteArray?,
    ): String = archive(catalog, serializedDetails, dependencies, catalogDependencies, resourceEntries, publisher = publisher, assetBytes = assetBytes).use {
        PortableCatalogCanonicalizer.currentQualifiedFingerprint(it).toString()
    }

    fun fingerprint(content: CatalogContent): String = archive(
        content.catalog,
        content.resourceDetails.mapValues { (_, detail) -> objectMapper.writeValueAsBytes(detail) },
        content.dependencies,
        content.catalogDependencies,
        content.resourceEntries,
        includeSerializedDetails = false,
        publisher = content.publisher,
        hasAsset = content.assetContents::containsKey,
    ) { contentPath ->
        content.assetContents[contentPath]
    }.use { PortableCatalogCanonicalizer.currentQualifiedFingerprint(it).toString() }

    /**
     * The content hash of [content] (contract ADR 0003, amendment of 2026-10-22; ADR 0034): its V6
     * fingerprint, `v6:<hex>`, over its content only (no name, release block or presentation). Two
     * releases with the same content hash are the same release. Pinned to V6 rather than "current", so
     * a later algorithm cannot silently change what a recorded content hash means.
     */
    fun contentHash(content: CatalogContent): String = archive(
        content.catalog,
        content.resourceDetails.mapValues { (_, detail) -> objectMapper.writeValueAsBytes(detail) },
        content.dependencies,
        content.catalogDependencies,
        content.resourceEntries,
        includeSerializedDetails = false,
        publisher = content.publisher,
        hasAsset = content.assetContents::containsKey,
    ) { contentPath ->
        content.assetContents[contentPath]
    }.use(Companion::contentHash)

    /**
     * The complete per-resource fingerprint of every `type/key` resource of [content]
     * ([PortableCatalogCanonicalizer.completePerResourceFingerprints], the per-resource form of V5):
     * what a resource is compared by, between releases, drafts and merges.
     */
    fun completePerResourceFingerprints(content: CatalogContent): Map<String, String> = archive(
        content.catalog,
        content.resourceDetails.mapValues { (_, detail) -> objectMapper.writeValueAsBytes(detail) },
        content.dependencies,
        content.catalogDependencies,
        content.resourceEntries,
        includeSerializedDetails = false,
        publisher = content.publisher,
        hasAsset = content.assetContents::containsKey,
    ) { contentPath ->
        content.assetContents[contentPath]
    }.use(PortableCatalogCanonicalizer::completePerResourceFingerprints)

    fun fingerprint(
        catalog: CatalogInfo,
        resourceDetails: Map<String, ResourceDetail>,
        dependencies: List<DependencyRef>?,
        catalogDependencies: List<CatalogDependency>? = null,
        assetBytes: (contentUrl: String) -> ByteArray?,
    ): String = fingerprintFromSerializedDetails(
        catalog,
        resourceDetails.mapValues { (_, detail) -> objectMapper.writeValueAsBytes(detail) },
        dependencies,
        catalogDependencies,
        assetBytes = assetBytes,
    )

    fun matchesFingerprint(content: CatalogContent, expected: String): Boolean = archive(
        content.catalog,
        content.resourceDetails.mapValues { (_, detail) -> objectMapper.writeValueAsBytes(detail) },
        content.dependencies,
        content.catalogDependencies,
        content.resourceEntries,
        includeSerializedDetails = false,
        publisher = content.publisher,
        hasAsset = content.assetContents::containsKey,
    ) { contentPath ->
        content.assetContents[contentPath]
    }.use { archive ->
        // Wire v8 names the algorithm (`v5:<hex>`) and is checked with exactly that one; a fingerprint
        // recorded before 2.0 is bare hex, checked against the legacy forms.
        if (QualifiedCatalogFingerprint.parse(expected) != null) {
            PortableCatalogCanonicalizer.matchesQualifiedFingerprint(archive, expected)
        } else {
            PortableCatalogCanonicalizer.matchesFingerprint(archive, expected)
        }
    }

    /** What the portable validator says of [content] as an archive, the fingerprint aside. */
    fun publishability(content: CatalogContent): CatalogValidationReport = archive(
        content.catalog,
        content.resourceDetails.mapValues { (_, detail) -> objectMapper.writeValueAsBytes(detail) },
        content.dependencies,
        content.catalogDependencies,
        content.resourceEntries,
        hasAsset = content.assetContents::containsKey,
    ) { contentPath ->
        content.assetContents[contentPath]
    }.use { archive -> CatalogValidator.validate(archive, CatalogValidationPolicy(verifyFingerprint = false)) }

    fun requirePublishable(content: CatalogContent) {
        val report = publishability(content)
        require(report.valid) {
            report.findings.joinToString(
                prefix = "Catalog contains non-publishable content: ",
                separator = "; ",
            ) { "${it.path}: ${it.message}" }
        }
    }

    fun perResourceFingerprintsFromSerializedDetails(
        serializedDetails: Map<String, ByteArray>,
        hasAsset: ((contentUrl: String) -> Boolean)? = null,
        assetBytes: (contentUrl: String) -> ByteArray?,
    ): Map<String, String> = archive(
        PLACEHOLDER_CATALOG,
        serializedDetails,
        null,
        hasAsset = hasAsset,
        assetBytes = assetBytes,
    ).use(PortableCatalogCanonicalizer::currentPerResourceFingerprints)

    /**
     * [completePerResourceFingerprints] of resources given as their serialized details (a source's
     * or an archive's, keyed `type/key`), with [assetBytes] for the binaries they name.
     */
    fun completePerResourceFingerprintsFromSerializedDetails(
        serializedDetails: Map<String, ByteArray>,
        assetBytes: (contentUrl: String) -> ByteArray?,
    ): Map<String, String> = archive(
        PLACEHOLDER_CATALOG,
        serializedDetails,
        null,
        assetBytes = assetBytes,
    ).use(PortableCatalogCanonicalizer::completePerResourceFingerprints)

    fun perResourceFingerprints(
        resourceDetails: Map<String, ResourceDetail>,
        hasAsset: ((contentUrl: String) -> Boolean)? = null,
        assetBytes: (contentUrl: String) -> ByteArray?,
    ): Map<String, String> = perResourceFingerprintsFromSerializedDetails(
        resourceDetails.mapValues { (_, detail) -> objectMapper.writeValueAsBytes(detail) },
        hasAsset,
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
            "${entry.type}/${entry.key}" to
                catalogClient.fetchBinaryContent(entry.detailUrl, manifestUrl, authType, credential)
        }
        return fingerprintFromSerializedDetails(
            manifest.catalog,
            serializedDetails,
            manifest.dependencies,
            manifest.catalogDependencies,
            manifest.resources,
            manifest.publisher,
        ) { contentUrl ->
            catalogClient.fetchBinaryContent(contentUrl, manifestUrl, authType, credential)
        }
    }

    private fun archive(
        catalog: CatalogInfo,
        serializedDetails: Map<String, ByteArray>,
        dependencies: List<DependencyRef>?,
        catalogDependencies: List<CatalogDependency>? = null,
        resourceEntries: List<ResourceEntry> = emptyList(),
        includeSerializedDetails: Boolean = true,
        publisher: PublisherInfo = PublisherInfo("Epistola"),
        /**
         * Whether a binary exists, answered without loading it. Given, a binary is read only when the
         * contract opens it, and not kept: the fingerprints open an image's bytes and never a font
         * face's, so a font's faces (megabytes) are not loaded for them. Null loads every binary up
         * front, for a source that can only tell by fetching.
         */
        hasAsset: ((contentUrl: String) -> Boolean)? = null,
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
        val binaryPaths = details.values.flatMap { detail -> detail.resource.contentPaths() }.distinct()
        val loaded: Map<String, ByteArray> = if (hasAsset == null) {
            binaryPaths.mapNotNull { path -> assetBytes(path)?.let { path to it } }.toMap()
        } else {
            emptyMap()
        }
        val binaries = if (hasAsset == null) loaded.keys else binaryPaths.filterTo(mutableSetOf(), hasAsset)
        val content = detailContent + loaded
        return CatalogArchive(
            manifest = CatalogManifest(
                schemaVersion = CATALOG_SCHEMA_VERSION,
                catalog = catalog,
                publisher = publisher,
                release = ReleaseInfo("0.0.0"),
                resources = resourceEntries,
                dependencies = dependencies,
                catalogDependencies = catalogDependencies,
            ),
            resourceDetails = details,
            paths = detailContent.keys + binaries,
            content = { path ->
                val bytes = content[path] ?: path.takeIf { it in binaries }?.let(assetBytes)
                ByteArrayInputStream(requireNotNull(bytes) { "Missing catalog content: $path" })
            },
        )
    }

    /**
     * Every binary a resource carries: an image's own, and each of a font family's faces.
     *
     * Resolved through `contentPath()` rather than read from `contentUrl`, which a catalog written
     * at wire v7 omits entirely -- the binary is filed where its hash says. Reading the field
     * directly would find nothing and quietly fingerprint the catalog without its content.
     */
    private fun CatalogResource.contentPaths(): List<String> = when (this) {
        is ImageResource -> listOf(contentPath())
        is FontResource -> variants.map { it.contentPath() }
        else -> emptyList()
    }

    companion object {
        private val PLACEHOLDER_CATALOG = CatalogInfo("fingerprints", "Fingerprints")

        /** The algorithm a content hash is computed with. */
        val CONTENT_HASH_ALGORITHM: app.epistola.catalog.canonical.CatalogFingerprintVersion = app.epistola.catalog.canonical.CatalogFingerprintVersion.V6

        /** The content hash ([contentHash]) of [archive], as an archive holds the release. */
        fun contentHash(archive: CatalogArchive): String = PortableCatalogCanonicalizer.qualifiedFingerprint(archive, CONTENT_HASH_ALGORITHM).toString()

        /** Whether [fingerprint] is a content hash: a fingerprint of the content-hash algorithm. */
        fun isContentHash(fingerprint: String?): Boolean = fingerprint != null && QualifiedCatalogFingerprint.parse(fingerprint)?.version == CONTENT_HASH_ALGORITHM
    }
}
