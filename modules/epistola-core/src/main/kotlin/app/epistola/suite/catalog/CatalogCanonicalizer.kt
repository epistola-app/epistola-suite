package app.epistola.suite.catalog

import app.epistola.catalog.protocol.AssetResource
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.DependencyRef
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.suite.fonts.model.sha256Hex
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
 */
class CatalogCanonicalizer(private val objectMapper: ObjectMapper) {

    /**
     * Canonical JSON of [value]: serialize with the configured mapper (keeps
     * registered modules + polymorphic typing), then recursively sort every
     * object's keys so the byte form is independent of property/map iteration
     * order. Array order is preserved (arrays are ordered content).
     */
    private fun canonicalJson(value: Any): String = objectMapper.writeValueAsString(sortKeys(objectMapper.valueToTree(value)))

    private fun sortKeys(node: JsonNode): JsonNode = when (node) {
        is ObjectNode -> objectMapper.createObjectNode().also { sorted ->
            node.propertyNames().sorted().forEach { name -> sorted.set(name, sortKeys(node.get(name))) }
        }
        is ArrayNode -> objectMapper.createArrayNode().also { arr ->
            node.forEach { arr.add(sortKeys(it)) }
        }
        else -> node
    }

    fun fingerprint(content: CatalogContent): String = fingerprint(content.catalog, content.resourceDetails, content.dependencies) { filename ->
        content.assetContents[filename]
    }

    /**
     * Fingerprint of a catalog fetched from a source URL (classpath / file /
     * https). Spring-free: depends only on a [CatalogClient]. Used by
     * [CatalogFingerprintService] and the bundled-catalog drift test.
     */
    fun fingerprintFromSource(
        catalogClient: CatalogClient,
        manifestUrl: String,
        authType: AuthType,
        credential: String?,
    ): String {
        val manifest = catalogClient.fetchManifest(manifestUrl, authType, credential)
        val details = LinkedHashMap<String, ResourceDetail>()
        val assets = LinkedHashMap<String, ByteArray>()
        for (entry in manifest.resources) {
            val detail = catalogClient.fetchResourceDetail(entry.detailUrl, manifestUrl, authType, credential)
            details["${entry.type}/${entry.slug}"] = detail
            val resource = detail.resource
            if (resource is AssetResource) {
                val filename = resource.contentUrl.removePrefix("./resources/asset/")
                assets[filename] = catalogClient.fetchBinaryContent(resource.contentUrl, manifestUrl, authType, credential)
            }
        }
        return fingerprint(manifest.catalog, details, manifest.dependencies) { filename -> assets[filename] }
    }

    fun fingerprint(
        catalog: CatalogInfo,
        resourceDetails: Map<String, ResourceDetail>,
        dependencies: List<DependencyRef>?,
        assetBytes: (filename: String) -> ByteArray?,
    ): String {
        val sb = StringBuilder()
        sb.append(catalog.slug).append(' ')
            .append(catalog.name).append(' ')
            .append(catalog.description ?: "").append('\n')

        for (key in resourceDetails.keys.sorted()) {
            val resource = resourceDetails.getValue(key).resource
            val json = canonicalJson(resource)
            val assetHash = if (resource is AssetResource) {
                val filename = resource.contentUrl.removePrefix("./resources/asset/")
                assetBytes(filename)?.let { sha256Hex(it) } ?: "MISSING"
            } else {
                ""
            }
            sb.append(key).append(' ').append(json).append(' ').append(assetHash).append('\n')
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
}
