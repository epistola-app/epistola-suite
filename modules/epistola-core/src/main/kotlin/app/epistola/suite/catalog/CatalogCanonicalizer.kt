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
 * What is hashed (in this exact order):
 *  1. identity line: `slug   name   description`;
 *  2. each resource, sorted by `"$type/$slug"`, as
 *     `key   <canonical resource JSON>   <asset-bytes hash | "" | "MISSING">`;
 *  3. dependencies line: cross-catalog refs as `type|catalogKey|slug`, sorted,
 *     `;`-joined.
 *
 * Excluded by construction: `release.*`, `schemaVersion`, every `updatedAt`,
 * `detailUrl` — none are part of the resource payloads. Asset binary bytes are
 * folded in via their own SHA-256 (mirrors the font family fingerprint), so a
 * swapped image flips the fingerprint even when its JSON is unchanged.
 *
 * **Determinism:** numbers are canonicalized as `BigDecimal` (exact source
 * digits), never round-tripped through `Double`/`Float.toString` — whose
 * algorithm changed across JDKs (JDK-4511638). The bundled drift gate compares
 * a fingerprint committed on one JDK against one recomputed on another (CI),
 * so this must be JDK-independent. See
 * [`docs/catalog-versioning.md`](../../../../../../../../docs/catalog-versioning.md).
 */
class CatalogCanonicalizer(private val objectMapper: ObjectMapper) {

    private val bigDecimalReader = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    /**
     * Canonical JSON: parse [json] into a tree with floats as `BigDecimal`,
     * recursively sort every object's keys (array order preserved — arrays are
     * ordered content), then compact-write. Independent of property/map
     * iteration order and of JDK floating-point formatting.
     */
    private fun canonical(json: String): String = objectMapper.writeValueAsString(sortKeys(bigDecimalReader.readTree(json)))

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

    private fun assetFilename(contentUrl: String): String = contentUrl.substringAfterLast('/')

    // ── DB / working-copy path (typed resources) ─────────────────────────────

    fun fingerprint(content: CatalogContent): String = fingerprint(content.catalog, content.resourceDetails, content.dependencies) { filename ->
        content.assetContents[filename]
    }

    fun fingerprint(
        catalog: CatalogInfo,
        resourceDetails: Map<String, ResourceDetail>,
        dependencies: List<DependencyRef>?,
        assetBytes: (filename: String) -> ByteArray?,
    ): String {
        val entries = resourceDetails.map { (key, detail) ->
            val resource = detail.resource
            val json = canonical(objectMapper.writeValueAsString(resource))
            val assetHash = if (resource is app.epistola.catalog.protocol.AssetResource) {
                assetBytes(assetFilename(resource.contentUrl))?.let { sha256Hex(it) } ?: "MISSING"
            } else {
                ""
            }
            Entry(key, json, assetHash)
        }
        return hash(catalog, entries, dependencies)
    }

    // ── Source path (raw JSON — no typed binding, JDK-independent) ────────────

    /**
     * Fingerprint of a catalog fetched from a source URL (classpath / file /
     * https). Reads each resource detail as **raw JSON** (never bound to typed
     * `Double`/`Float`), so the result is byte-stable across JDKs — required
     * because the bundled drift gate commits a value on one machine and
     * recomputes it on another. Spring-free: depends only on a [CatalogClient].
     */
    fun fingerprintFromSource(
        catalogClient: CatalogClient,
        manifestUrl: String,
        authType: AuthType,
        credential: String?,
    ): String {
        val manifest = catalogClient.fetchManifest(manifestUrl, authType, credential)
        val entries = manifest.resources.map { entry ->
            val rawDetail = catalogClient.fetchBinaryContent(entry.detailUrl, manifestUrl, authType, credential)
            val resourceNode = bigDecimalReader.readTree(rawDetail).get("resource")
            val json = objectMapper.writeValueAsString(sortKeys(resourceNode))
            val assetHash = if (entry.type == "asset") {
                val contentUrl = resourceNode.get("contentUrl")?.asString() ?: ""
                val bytes = catalogClient.fetchBinaryContent(contentUrl, manifestUrl, authType, credential)
                sha256Hex(bytes)
            } else {
                ""
            }
            Entry("${entry.type}/${entry.slug}", json, assetHash)
        }
        return hash(manifest.catalog, entries, manifest.dependencies)
    }
}
