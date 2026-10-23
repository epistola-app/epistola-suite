// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.common.ids.TenantKey
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** The function a stored baseline's values come from: [CatalogFingerprintService.resourceFingerprints]. */
const val BASELINE_ALGORITHM: String = "complete-v5"

/** Count bound of the per-resource fingerprint cache; there are a handful of bundled catalogs. */
private const val MAX_CACHED_CLASSPATH_CATALOGS: Long = 64

/**
 * Computes the deterministic content fingerprint of a catalog — from the live
 * working copy (DB) or from a source URL (classpath / file / https). The
 * canonicalization itself lives in [CatalogCanonicalizer]; this component wires
 * it to the DB ([CatalogContentBuilder]) and the source fetcher
 * ([CatalogClient]).
 *
 * See [`docs/catalog-versioning.md`](../../../../../../../../docs/catalog-versioning.md).
 */
@Component
class CatalogFingerprintService(
    private val objectMapper: ObjectMapper,
    private val contentBuilder: CatalogContentBuilder,
    private val catalogClient: CatalogClient,
) {
    private val canonicalizer = CatalogCanonicalizer(objectMapper)

    /** Fingerprint of the live working copy of a catalog. */
    // Per-resource fingerprints of a classpath source are a pure function of bundle content, which
    // cannot change while this JVM runs, yet every tenant that installs the bundled catalog
    // canonicalised and hashed all of its resources again (most of what RegisterCatalog costs per
    // tenant). Cached per manifest URL with a small count bound; there are a handful of bundled
    // catalogs. file: and HTTP sources are computed every time, as before.
    private val classpathResourceFingerprints = Caffeine.newBuilder()
        .maximumSize(MAX_CACHED_CLASSPATH_CATALOGS)
        .build<String, Map<String, String>>()

    fun fingerprint(tenantKey: TenantKey, catalogKey: CatalogKey): String = canonicalizer.fingerprint(contentBuilder.build(tenantKey, catalogKey))

    fun evaluate(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        expected: String?,
    ): CatalogFingerprintEvaluation {
        val content = contentBuilder.build(tenantKey, catalogKey)
        return CatalogFingerprintEvaluation(
            current = canonicalizer.fingerprint(content),
            matchesExpected = expected != null && canonicalizer.matchesFingerprint(content, expected),
        )
    }

    fun fingerprint(content: CatalogContent): String = canonicalizer.fingerprint(content)

    /** The content hash of [content] ([CatalogCanonicalizer.contentHash]): what identifies a release. */
    fun contentHash(content: CatalogContent): String = canonicalizer.contentHash(content)

    fun matchesFingerprint(content: CatalogContent, expected: String): Boolean = canonicalizer.matchesFingerprint(content, expected)

    fun requirePublishable(content: CatalogContent) = canonicalizer.requirePublishable(content)

    fun publishability(content: CatalogContent) = canonicalizer.publishability(content)

    /**
     * Fingerprint of a catalog fetched from a source URL. Used to verify the
     * committed fingerprint of bundled catalogs has not drifted.
     */
    fun fingerprintFromSource(manifestUrl: String, authType: AuthType, credential: String?): String = canonicalizer.fingerprintFromSource(catalogClient, manifestUrl, authType, credential)

    /**
     * Per-resource digests of a live working copy in the V3 form, keyed `"type/slug"`. Recorded per
     * release (`release_entries.fingerprint`, which the REST release detail shows) and nothing else:
     * it leaves out what wire v8 added to a resource, so nothing that tells resources apart compares
     * it. That is [resourceFingerprints].
     */
    fun perResourceFingerprints(content: CatalogContent): Map<String, String> = canonicalizer.perResourceFingerprints(content.resourceDetails) { contentPath -> content.assetContents[contentPath] }

    /**
     * What tells two states of a resource apart, keyed `"type/key"`: the complete per-resource
     * fingerprint (contract `completePerResourceFingerprints`, the per-resource form of V5), over
     * everything V5 covers of the resource, a template's description and a variant's metadata
     * included. The one function every comparison of resources uses: a merge's three sides, what a
     * next release changed, and the baselines a draft records ([storeBaseline]).
     */
    fun resourceFingerprints(content: CatalogContent): Map<String, String> = canonicalizer.completePerResourceFingerprints(content)

    /** [resourceFingerprints] of an archive as it was received. */
    fun resourceFingerprints(archive: app.epistola.catalog.archive.CatalogArchive): Map<String, String> = app.epistola.catalog.canonical.CatalogCanonicalizer.completePerResourceFingerprints(archive)

    /** The fingerprint of a catalog setting's value given in its fixed [canonical] shape. */
    fun settingFingerprint(canonical: Any?): String = app.epistola.suite.fonts.model.sha256Hex(objectMapper.writeValueAsBytes(canonical))

    /**
     * Every entry of [content] a merge compares, by `type/key`: its resources ([resourceFingerprints])
     * and its settings ([app.epistola.suite.catalog.merge.CatalogSetting]: `catalog/<key>`, and
     * `catalogDependency/<catalogKey>` per catalog it declares).
     */
    fun entryFingerprints(catalogKey: CatalogKey, content: CatalogContent): Map<String, String> = resourceFingerprints(content) +
        app.epistola.suite.catalog.merge.CatalogSetting.of(catalogKey, content).associate { it.contentKey to settingFingerprint(it.canonical) }

    /**
     * [fingerprints] as a stored baseline (`catalog_versions.base_fingerprints`,
     * `catalog_draft_merges.held_fingerprints`), naming the function that computed them, so a
     * baseline is never compared with values of another ([readBaseline]).
     */
    fun storeBaseline(fingerprints: Map<String, String>): String = objectMapper.writeValueAsString(
        mapOf("algorithm" to BASELINE_ALGORITHM, "entries" to fingerprints.toSortedMap()),
    )

    /**
     * A stored baseline's fingerprints, or null when it was computed by another function (one stored
     * before baselines named theirs holds V3 values): such a baseline is as good as none, and the
     * caller compares with the release's content instead.
     */
    fun readBaseline(json: String?): Map<String, String>? = readBaseline(json?.let(objectMapper::readTree))

    /** [readBaseline] of a baseline already read as JSON (a `@Json` column). */
    fun readBaseline(node: tools.jackson.databind.JsonNode?): Map<String, String>? {
        if (node == null || !node.isObject || node.path("algorithm").asString(null) != BASELINE_ALGORITHM) return null
        val entries = node.path("entries")
        if (!entries.isObject) return null
        return entries.properties().associate { (key, value) -> key to value.asString() }
    }

    /**
     * [resourceFingerprints] of a catalog fetched from a source URL, the same fetch loop as
     * [fingerprintFromSource]. Stored at register and upgrade as the installed release's baseline
     * (`catalogs.installed_resource_fingerprints`, through [storeBaseline]) and computed again for
     * the incoming release when previewing an upgrade: source against source, so a CHANGED verdict
     * means the publisher changed that resource, a template's description included.
     */
    fun resourceFingerprintsFromSource(manifestUrl: String, authType: AuthType, credential: String?): Map<String, String> {
        if (manifestUrl.startsWith("classpath:")) {
            return classpathResourceFingerprints.get(manifestUrl) {
                computeResourceFingerprintsFromSource(it, authType, credential)
            }
        }
        return computeResourceFingerprintsFromSource(manifestUrl, authType, credential)
    }

    /**
     * [resourceFingerprints] of resources given as an archive holds them: serialized details keyed
     * `type/key`, with [assetBytes] for the binaries they name.
     */
    fun resourceFingerprints(serializedDetails: Map<String, ByteArray>, assetBytes: (contentUrl: String) -> ByteArray?): Map<String, String> = canonicalizer.completePerResourceFingerprintsFromSerializedDetails(serializedDetails, assetBytes)

    private fun computeResourceFingerprintsFromSource(manifestUrl: String, authType: AuthType, credential: String?): Map<String, String> {
        val manifest = catalogClient.fetchManifest(manifestUrl, authType, credential)
        val detailBytes = LinkedHashMap<String, ByteArray>()
        for (entry in manifest.resources) {
            detailBytes["${entry.type}/${entry.key}"] =
                catalogClient.fetchBinaryContent(entry.detailUrl, manifestUrl, authType, credential)
        }
        return canonicalizer.completePerResourceFingerprintsFromSerializedDetails(detailBytes) { contentUrl ->
            catalogClient.fetchBinaryContent(contentUrl, manifestUrl, authType, credential)
        }
    }
}

data class CatalogFingerprintEvaluation(
    val current: String,
    val matchesExpected: Boolean,
)
