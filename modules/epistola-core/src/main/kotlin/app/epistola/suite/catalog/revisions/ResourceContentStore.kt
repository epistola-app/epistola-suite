// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ImageResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.model.sha256Hex
import app.epistola.suite.storage.AssetContentStore
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Retains the content of a released catalog as immutable, content-addressed revisions.
 *
 * Sole owner of the `resource_contents` / `resource_binaries` SQL, the way
 * `CatalogUpstreamCheckStore` owns the upstream-check SQL. It is handed the open release
 * transaction, so a release and the content it retains commit together or not at all.
 *
 * **Deduplicated by digest within a tenant.** Re-releasing a catalog whose only edit is one
 * template writes one payload and reuses the rest; releasing content byte-identical to a previous
 * release writes nothing at all.
 *
 * **A template is a content and one per variant** ([TemplateContents], ADR 0033): each variant is
 * a row of its own ([VariantRows]) naming its content, with its model. A
 * bundled template is 20–70 KB of JSON, so keeping every variant in one payload would rewrite all of
 * them whenever one changed, defeat deduplication, and load the lot to render one variant.
 *
 * The digest is **not** the catalog fingerprint. It is internal storage identity, computed over the
 * stored payload; the fingerprint is computed by the contract over the wire form and is what
 * appears on the wire (ADR 0026 §5). Neither may be derived from the other.
 */
@Component
class ResourceContentStore(
    private val objectMapper: ObjectMapper,
    private val contentStore: AssetContentStore,
) {

    /**
     * Writes a revision for every resource in [content], and the binary references that keep its
     * bytes from being collected.
     *
     * @param settings what the suite keeps beside a resource's released form, keyed `"type/slug"`:
     *   an object whose fields go next to `resource` (a code list's `source`, ADR 0033). A release cut
     *   here passes its draft's; a received one has none.
     * @return each resource's revision digest, keyed `"type/slug"` as the catalog content keys it.
     */
    fun retain(handle: Handle, tenantKey: TenantKey, content: CatalogContent, settings: Map<String, ObjectNode> = emptyMap()): Retained {
        val hashes = content.resourceDetails.values.flatMap { binaryHashes(it.resource) }
        // The bytes a received release carries go in first, so the contents can link to them.
        storeCarried(content, hashes)

        val variants = mutableMapOf<String, List<VariantRows.Released>>()
        val contents = content.resourceDetails.mapValues { (key, detail) ->
            val resource = detail.resource
            val own = settings["${resource.type}/${resource.key}"]
            if (resource is TemplateResource) {
                // A template is its content and one per variant (TemplateContents), as its draft stores it.
                val descriptions = TemplateContents.descriptions(own)
                val default = resource.variants.firstOrNull { it.isDefault } ?: resource.variants.firstOrNull()
                variants[key] = resource.variants.map { variant ->
                    val model = if (variant.key == default?.key) resource.templateModel else variant.templateModel
                    val description = descriptions[variant.key]
                    val hash = write(handle, tenantKey, TemplateContents.VARIANT_KIND, TemplateContents.part(variant, model, description, objectMapper))
                    VariantRows.Released(variant.key, hash, variant.title ?: variant.key, description)
                }
                return@mapValues write(handle, tenantKey, resource.type, TemplateContents.head(resource, objectMapper))
            }
            write(handle, tenantKey, resource.type, payloadOf(resource, own))
        }
        return Retained(contents, variants)
    }

    /**
     * What a release retained: each resource's content hash, keyed `"type/slug"` as the catalog
     * content keys it, and each template's variants, keyed the same way (their rows, [VariantRows]).
     */
    data class Retained(val contents: Map<String, String>, val variants: Map<String, List<VariantRows.Released>>)

    /** The resource as stored: its released form in the envelope, beside it the settings only this suite keeps. */
    private fun payloadOf(resource: CatalogResource, settings: ObjectNode?): JsonNode {
        val node = objectMapper.valueToTree<JsonNode>(resource)
        // Every type is stored in the envelope, as its draft stores it, so a draft and the release cut
        // from it share one content.
        return objectMapper.createObjectNode().also { envelope ->
            envelope.set("resource", node)
            settings?.properties()?.forEach { (name, value) -> envelope.set(name, value) }
        }
    }

    /**
     * Stores one resource's content as a draft holds it (ADR 0033), and returns its content hash:
     * the same content a release of it would store, so a draft and the release cut from it share one
     * row. Draft rows (`release_resources` with a `version_id`) name it.
     */
    fun store(handle: Handle, tenantKey: TenantKey, kind: String, payload: JsonNode): String = write(handle, tenantKey, kind, payload)

    /**
     * Writes one content and returns its content hash, with what it refers to
     * ([ContentReferences], `resource_references`).
     *
     * The hash is taken over the canonical serialisation, not over whatever key order the mapper
     * produced, so the same content hashes the same however it was built — and JSONB does not
     * preserve key order anyway, so the stored bytes could never serve as the identity.
     *
     * Content stored again is touched (`stored_at`), which locks its row until the transaction
     * ends: the collector ([ResourceContentCollector]) skips a locked row and takes only content
     * stored longer ago than its grace period, so content a save is about to point at again is never
     * collected from under it (ADR 0033).
     */
    private fun write(handle: Handle, tenantKey: TenantKey, kind: String, payload: JsonNode): String {
        val canonical = objectMapper.writeValueAsString(sortKeys(payload))
        val digest = sha256Hex(canonical.toByteArray())
        handle.createUpdate(
            """
            INSERT INTO resource_contents (tenant_key, content_hash, kind, payload)
            VALUES (:t, :digest, :kind, CAST(:payload AS JSONB))
            ON CONFLICT (tenant_key, content_hash) DO UPDATE SET stored_at = NOW()
            """,
        )
            .bind("t", tenantKey)
            .bind("digest", digest)
            .bind("kind", kind)
            .bind("payload", canonical)
            .execute()
        writeReferences(handle, tenantKey, digest, ContentReferences.of(kind, payload))
        linkBinaries(handle, tenantKey, digest, binaryHashes(kind, payload))
        return digest
    }

    /** The content hash [payload] of [kind] is stored under, without storing it. */
    fun hashOf(payload: JsonNode): String = sha256Hex(objectMapper.writeValueAsString(sortKeys(payload)).toByteArray())

    /**
     * Every binary a stored content needs, from its JSON: an image's own, each of a font's faces.
     * Read from the content, not the resource, so a draft and a release link alike (ADR 0033).
     */
    private fun binaryHashes(kind: String, payload: JsonNode): List<String> {
        val resource = StoredContent.releasedForm(kind, payload)
        return when (kind) {
            "image" -> listOfNotNull(resource.get("contentHash")?.takeIf { it.isString }?.asString())
            "font" -> resource.path("variants").mapNotNull { it.get("contentHash")?.takeIf { hash -> hash.isString }?.asString() }
            else -> emptyList()
        }
    }

    /**
     * Links [digest] to the bytes it needs (`resource_binaries`), so they are kept while it is. A hash
     * whose bytes are not stored yet is not linked; the bundled faces are linked when the system
     * fonts are seeded.
     */
    private fun linkBinaries(handle: Handle, tenantKey: TenantKey, digest: String, hashes: List<String>) {
        if (hashes.isEmpty()) return
        handle.createUpdate(
            """
            INSERT INTO resource_binaries (tenant_key, content_hash, binary_hash)
            SELECT :t, :digest, ac.content_hash FROM asset_content ac WHERE ac.content_hash IN (<hashes>)
            ON CONFLICT DO NOTHING
            """,
        )
            .bind("t", tenantKey)
            .bind("digest", digest)
            .bindList("hashes", hashes.distinct())
            .execute()
    }

    private fun writeReferences(handle: Handle, tenantKey: TenantKey, contentHash: String, references: Set<ContentReference>) {
        if (references.isEmpty()) return
        val batch = handle.prepareBatch(
            """
            INSERT INTO resource_references (tenant_key, content_hash, alias, resource_type, resource_key)
            VALUES (:t, :hash, :alias, :type, :key)
            ON CONFLICT DO NOTHING
            """,
        )
        references.forEach { reference ->
            batch.bind("t", tenantKey).bind("hash", contentHash).bind("alias", reference.alias).bind("type", reference.type).bind("key", reference.key).add()
        }
        batch.execute()
    }

    /**
     * The bytes [content] carries that no asset of the tenant holds: a release received beside the
     * others (ADR 0032) brings resources no version has, so nothing stored their bytes. Stored in
     * the shared scope, as published content is, so the release keeps them; the retained revision's
     * binary reference is what keeps them from being collected. Bytes that do not hash to what their
     * resource names are not stored.
     */
    private fun storeCarried(content: CatalogContent, hashes: List<String>): List<String> {
        val carried = content.resourceDetails.values.flatMap { detail ->
            when (val resource = detail.resource) {
                is ImageResource -> listOf(Triple(resource.contentHash, resource.contentPath(), resource.mediaType))
                is FontResource -> resource.variants.map { Triple(it.contentHash, it.contentPath(), it.mediaType) }
                else -> emptyList()
            }
        }
        return carried.filter { it.first in hashes }.mapNotNull { (hash, path, mediaType) ->
            // Already stored: neither hashed nor sent again (a system catalog's faces, on every tenant).
            if (contentStore.exists(hash)) return@mapNotNull hash
            val bytes = content.assetContents[path]?.takeIf { sha256Hex(it) == hash } ?: return@mapNotNull null
            contentStore.putIfAbsent(hash, bytes, mediaType, bytes.size.toLong())
            hash
        }
    }

    /** Every binary a resource needs: an image's own, and each of a font family's faces. */
    private fun binaryHashes(resource: CatalogResource): List<String> = when (resource) {
        is ImageResource -> listOf(resource.contentHash)
        is FontResource -> resource.variants.map { it.contentHash }
        else -> emptyList()
    }

    /** Recursively sorted object keys — the same canonical rule the contract's canonicaliser uses. */
    private fun sortKeys(node: JsonNode): JsonNode = when (node) {
        is ObjectNode -> objectMapper.createObjectNode().also { sorted ->
            node.propertyNames().sorted().forEach { name -> sorted.set(name, sortKeys(node.get(name))) }
        }

        is ArrayNode -> objectMapper.createArrayNode().also { sorted ->
            node.forEach { sorted.add(sortKeys(it)) }
        }

        else -> node
    }
}
