package app.epistola.suite.catalog

import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.DependencyRef
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.suite.fonts.model.sha256Hex
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Pure, Spring-free canonicalization + SHA-256 of a catalog's content. Kept
 * separate from [CatalogFingerprintService] so its determinism is unit-testable
 * without the DB / HTTP collaborators.
 *
 * **One definition.** Every fingerprint — bundled drift gate, export-stamped,
 * release-stored, working-copy drift — is computed from the **serialized
 * resource-detail JSON** (the exact wire form): parse with floats as
 * `BigDecimal`, take `resource`, recursively sort object keys, compact-write,
 * SHA-256 over the sorted entries + identity + deps. So the value stamped into
 * an exported manifest equals what any consumer recomputes from that manifest's
 * detail bytes via [fingerprintFromSource] — by construction. There is no
 * separate "typed object" path that could diverge for `Float`/`Double` fields.
 *
 * What is hashed (in this exact order):
 *  1. identity line: `slug   name   description`;
 *  2. each resource, sorted by `"$type/$slug"`, as
 *     `key   <canonical resource JSON>   <asset-bytes hash | "" | "MISSING">`;
 *  3. dependencies line: cross-catalog refs as `type|catalogKey|slug`, sorted,
 *     `;`-joined.
 *
 * Excluded by construction: `release.*`, `schemaVersion`, every `updatedAt`,
 * `detailUrl` — none are part of the `resource` payload. Asset binary bytes
 * are folded in via their own SHA-256 (mirrors the font family fingerprint).
 *
 * **Determinism:** numbers are canonicalized as `BigDecimal` (exact source
 * digits), never round-tripped through `Double`/`Float.toString` — whose
 * algorithm changed across JDKs (JDK-4511638). See
 * [`docs/catalog-versioning.md`](../../../../../../../../docs/catalog-versioning.md).
 */
class CatalogCanonicalizer(private val objectMapper: ObjectMapper) {

    private val bigDecimalReader = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    private fun sortKeys(node: JsonNode): JsonNode = when (node) {
        is ObjectNode -> objectMapper.createObjectNode().also { sorted ->
            node.propertyNames().sorted().forEach { name -> sorted.set(name, sortKeys(node.get(name))) }
        }
        is ArrayNode -> objectMapper.createArrayNode().also { arr ->
            node.forEach { arr.add(sortKeys(it)) }
        }
        else -> node
    }

    private data class Entry(val key: String, val canonicalJson: String, val assetHash: String)

    /**
     * The single per-resource pipeline: each value is the serialized
     * `ResourceDetail` JSON (raw file bytes for the source path; serialized
     * in-memory detail for the DB/wire path). Parse → `resource` subtree →
     * sort keys → compact JSON; fold in asset bytes by `contentUrl`.
     */
    private fun entriesFromDetailBytes(
        detailBytesByKey: Map<String, ByteArray>,
        assetBytes: (contentUrl: String) -> ByteArray?,
    ): List<Entry> = detailBytesByKey.map { (key, bytes) ->
        val resourceNode = bigDecimalReader.readTree(bytes).get("resource")
        val json = objectMapper.writeValueAsString(sortKeys(resourceNode))
        val assetHash = if (resourceNode.get("type")?.asString() == "asset") {
            val contentUrl = resourceNode.get("contentUrl")?.asString() ?: ""
            assetBytes(contentUrl)?.let { sha256Hex(it) } ?: "MISSING"
        } else {
            ""
        }
        Entry(key, json, assetHash)
    }

    private fun hash(catalog: CatalogInfo, entries: List<Entry>, dependencies: List<DependencyRef>?): String {
        val sb = StringBuilder()
        sb.append(catalog.slug).append(' ')
            .append(catalog.name).append(' ')
            .append(catalog.description ?: "").append('\n')

        for (e in entries.sortedBy { it.key }) {
            sb.append(e.key).append(' ').append(e.canonicalJson).append(' ').append(e.assetHash).append('\n')
        }

        val deps = dependencies.orEmpty()
            .map { dep ->
                val type = when (dep) {
                    is DependencyRef.Theme -> "theme"
                    is DependencyRef.Stencil -> "stencil"
                    is DependencyRef.CodeList -> "codeList"
                    is DependencyRef.Font -> "font"
                    is DependencyRef.Asset -> "asset"
                }
                val cat = when (dep) {
                    is DependencyRef.Theme -> dep.catalogKey
                    is DependencyRef.Stencil -> dep.catalogKey
                    is DependencyRef.CodeList -> dep.catalogKey
                    is DependencyRef.Font -> dep.catalogKey
                    is DependencyRef.Asset -> ""
                }
                "$type|$cat|${dep.slug}"
            }
            .sorted()
            .joinToString(";")
        sb.append("deps ").append(deps).append('\n')

        return sha256Hex(sb.toString().toByteArray(Charsets.UTF_8))
    }

    // ── Wire / DB path: fingerprint of the serialized resource-detail form ────

    /**
     * Fingerprint of a catalog from already-serialized `ResourceDetail` JSON
     * (the exact bytes written to a ZIP / `catalog_releases` snapshot). This is
     * the value stamped into manifests and stored on release; it equals
     * [fingerprintFromSource] over the same bytes.
     */
    fun fingerprintFromSerializedDetails(
        catalog: CatalogInfo,
        serializedDetails: Map<String, ByteArray>,
        dependencies: List<DependencyRef>?,
        assetBytes: (contentUrl: String) -> ByteArray?,
    ): String = hash(catalog, entriesFromDetailBytes(serializedDetails, assetBytes), dependencies)

    fun fingerprint(content: CatalogContent): String = fingerprint(content.catalog, content.resourceDetails, content.dependencies) { contentUrl ->
        content.assetContents[contentUrl.removePrefix("./resources/asset/")]
    }

    fun fingerprint(
        catalog: CatalogInfo,
        resourceDetails: Map<String, ResourceDetail>,
        dependencies: List<DependencyRef>?,
        assetBytes: (contentUrl: String) -> ByteArray?,
    ): String {
        val serialized = resourceDetails.mapValues { (_, detail) -> objectMapper.writeValueAsBytes(detail) }
        return fingerprintFromSerializedDetails(catalog, serialized, dependencies, assetBytes)
    }

    // ── Source path (bundled drift gate): raw committed file bytes ────────────

    /**
     * Fingerprint of a catalog fetched from a source URL (classpath / file /
     * https). Reads each resource detail as the raw committed JSON — the
     * bundled drift gate commits a value on one machine and recomputes it on
     * another, so it never binds typed numbers. Spring-free: depends only on a
     * [CatalogClient].
     */
    fun fingerprintFromSource(
        catalogClient: CatalogClient,
        manifestUrl: String,
        authType: AuthType,
        credential: String?,
    ): String {
        val manifest = catalogClient.fetchManifest(manifestUrl, authType, credential)
        val detailBytes = LinkedHashMap<String, ByteArray>()
        for (entry in manifest.resources) {
            detailBytes["${entry.type}/${entry.slug}"] =
                catalogClient.fetchBinaryContent(entry.detailUrl, manifestUrl, authType, credential)
        }
        val entries = entriesFromDetailBytes(detailBytes) { contentUrl ->
            catalogClient.fetchBinaryContent(contentUrl, manifestUrl, authType, credential)
        }
        return hash(manifest.catalog, entries, manifest.dependencies)
    }
}
