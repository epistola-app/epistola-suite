// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ImageResource
import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.suite.catalog.CATALOG_SCHEMA_VERSION
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.model.sha256Hex
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * A release, read back: its content and the release metadata it was cut with.
 *
 * [release] comes from the manifest snapshot rather than being composed now, so an archive built
 * from this carries the version, timestamp and fingerprint the original did — which is what makes
 * the rebuilt bytes the released bytes rather than a new export of old content.
 */
data class RetainedRelease(
    val content: CatalogContent,
    val release: ReleaseInfo,
)

/**
 * Rebuilds the content of a named release from what that release retained.
 *
 * This is the read side of [ResourceRevisionStore] and [ReleaseEntryStore], and the point of
 * retaining anything: an earlier release stops being a fingerprint and a promise and becomes content
 * that can be reproduced, exported and rendered from, whatever the working copy has done since.
 *
 * **The output is byte-equivalent to what was released.** Canonical serialisation is a compatibility
 * surface (ADR 0026 §4): rebuilding a release must reproduce the same archive, and therefore the
 * same fingerprint, as the release that was cut. That equality is what `ReleaseRoundTripTest` pins,
 * and it is the only check that the stored shape is faithful — including that lifting a template's
 * variant models into revisions of their own loses nothing.
 */
@Component
class ReleaseContentAssembler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val releaseEntryStore: ReleaseEntryStore,
) {

    /**
     * The content of [version] of a catalog, or null when that release retained none — every
     * release cut before `V20260923201010`, which cannot be reconstructed from the working copy
     * and must not be guessed at from it.
     */
    fun assemble(tenantKey: TenantKey, catalogKey: CatalogKey, version: String): RetainedRelease? = jdbi.withHandle<RetainedRelease?, Exception> { handle ->
        // The flag, not the entry count: a catalog with no resources retains a release that
        // contains nothing, which is not the same as a release that kept nothing.
        if (!releaseEntryStore.retainedVersions(handle, tenantKey, catalogKey).contains(version)) return@withHandle null
        val entries = releaseEntryStore.entriesOf(handle, tenantKey, catalogKey, version)

        val snapshot = loadManifestSnapshot(handle, tenantKey, catalogKey, version)
        val payloads = loadPayloads(handle, tenantKey, entries.map { it.revisionDigest })

        val details = entries.associate { entry ->
            val payload = requireNotNull(payloads[entry.revisionDigest]) {
                "Release $version of ${catalogKey.value} names revision ${entry.revisionDigest}, which is not stored"
            }
            entry.contentKey to detailOf(handle, tenantKey, payload)
        }

        RetainedRelease(
            content = CatalogContent(
                // The catalog's own metadata and its dependencies are release metadata rather than
                // resource content, and the manifest snapshot already froze them at release time.
                catalog = snapshot.catalog,
                resourceEntries = entries.map { it.toResourceEntry() },
                resourceDetails = details,
                dependencies = snapshot.dependencies,
                assetContents = loadAssetContents(handle, tenantKey, details.values),
            ),
            release = snapshot.release,
        )
    }

    /**
     * One resource of a release, or null when the release does not contain it.
     *
     * The narrow read that rendering needs: a whole-release [assemble] loads every payload and every
     * binary, which is what an export wants and what a render must not pay for.
     */
    fun readResource(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        version: String,
        resourceType: String,
        resourceKey: String,
    ): CatalogResource? = jdbi.withHandle<CatalogResource?, Exception> { handle ->
        val digest = handle.createQuery(
            """
            SELECT revision_digest FROM release_entries
            WHERE tenant_key = :t AND catalog_key = :c AND version = :version
              AND resource_type = :type AND resource_key = :key
            """,
        )
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .bind("version", version)
            .bind("type", resourceType)
            .bind("key", resourceKey)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null) ?: return@withHandle null
        val payload = requireNotNull(loadPayloads(handle, tenantKey, listOf(digest))[digest]) {
            "Release $version of ${catalogKey.value} names revision $digest, which is not stored"
        }
        detailOf(handle, tenantKey, payload).resource
    }

    /**
     * The bytes a release holds under [contentHash], or null when no retained revision of this tenant
     * holds them.
     *
     * Read through `revision_binaries`, so it answers only for bytes a release retained -- never for an
     * asset that merely has the same hash today. Bundled font faces are the one exception: they live
     * on the classpath rather than in the content store, so a release holds their hash but no blob. A
     * bundled face is found by its hash and its bytes are checked against it, so a suite upgrade that
     * ships a different file fails the lookup instead of rendering different glyphs.
     */
    fun readBinary(tenantKey: TenantKey, contentHash: String): ByteArray? = jdbi.withHandle<ByteArray?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT c.content
            FROM revision_binaries b
            JOIN asset_content c ON c.scope = b.scope AND c.content_hash = b.content_hash
            WHERE b.tenant_key = :t AND b.content_hash = :hash
            LIMIT 1
            """,
        )
            .bind("t", tenantKey)
            .bind("hash", contentHash)
            .mapTo(ByteArray::class.java)
            .findOne()
            .orElse(null)
            ?: bundledFace(handle, tenantKey, contentHash)
    }

    private fun bundledFace(handle: Handle, tenantKey: TenantKey, contentHash: String): ByteArray? {
        val location = handle.createQuery(
            """
            SELECT classpath_location FROM font_variants
            WHERE tenant_key = :t AND content_hash = :hash AND source = 'CLASSPATH' AND classpath_location IS NOT NULL
            LIMIT 1
            """,
        )
            .bind("t", tenantKey)
            .bind("hash", contentHash)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null) ?: return null
        val bytes = javaClass.classLoader.getResourceAsStream(location)?.use { it.readBytes() } ?: return null
        return bytes.takeIf { sha256Hex(it) == contentHash }
    }

    /** The latest release of a catalog that retained its content, or null when none has. */
    fun assembleLatest(tenantKey: TenantKey, catalogKey: CatalogKey): RetainedRelease? {
        val latest = jdbi.withHandle<String?, Exception> { handle ->
            releaseEntryStore.retainedVersions(handle, tenantKey, catalogKey).firstOrNull()
        }
        return latest?.let { assemble(tenantKey, catalogKey, it) }
    }

    /**
     * One stored payload back into a protocol resource, with its child revisions substituted in.
     *
     * Wrapped in a `ResourceDetail` envelope rather than read as a `CatalogResource` directly: the
     * contract declares the type discrimination on that property, so going through the envelope is
     * what makes `type` select the right class instead of duplicating the mapping here.
     */
    private fun detailOf(handle: Handle, tenantKey: TenantKey, payload: JsonNode): ResourceDetail {
        val resolved = resolveRefs(handle, tenantKey, payload)
        val envelope = objectMapper.createObjectNode()
            .put("schemaVersion", CATALOG_SCHEMA_VERSION)
        envelope.set("resource", resolved)
        return objectMapper.treeToValue(envelope, ResourceDetail::class.java)
    }

    /** Replaces every [REVISION_REF_FIELD] object with the payload it names, recursively. */
    private fun resolveRefs(handle: Handle, tenantKey: TenantKey, node: JsonNode): JsonNode = when {
        node is ObjectNode && node.size() == 1 && node.has(REVISION_REF_FIELD) -> {
            val digest = node.get(REVISION_REF_FIELD).asString()
            val child = requireNotNull(loadPayloads(handle, tenantKey, listOf(digest))[digest]) {
                "A retained payload references revision $digest, which is not stored"
            }
            resolveRefs(handle, tenantKey, child)
        }

        node is ObjectNode -> objectMapper.createObjectNode().also { copy ->
            node.propertyNames().forEach { name -> copy.set(name, resolveRefs(handle, tenantKey, node.get(name))) }
        }

        node is ArrayNode -> objectMapper.createArrayNode().also { copy ->
            node.forEach { copy.add(resolveRefs(handle, tenantKey, it)) }
        }

        else -> node
    }

    /**
     * The binaries the rebuilt content needs, keyed by the archive path each takes — the same keys
     * `CatalogContentBuilder` produces, because the canonicaliser and the archive writer both ask
     * for them that way.
     *
     * Read through `revision_binaries`, so the bytes come from what the release retained rather than
     * from whatever an asset of that name holds now.
     */
    private fun loadAssetContents(handle: Handle, tenantKey: TenantKey, details: Collection<ResourceDetail>): Map<String, ByteArray> {
        val pathsByHash = LinkedHashMap<String, MutableList<String>>()
        for (detail in details) {
            when (val resource = detail.resource) {
                is ImageResource -> pathsByHash.getOrPut(resource.contentHash) { mutableListOf() }.add(resource.contentPath())

                is FontResource -> resource.variants.forEach {
                    pathsByHash.getOrPut(it.contentHash) { mutableListOf() }.add(it.contentPath())
                }

                else -> Unit
            }
        }
        if (pathsByHash.isEmpty()) return emptyMap()

        val bytesByHash = handle.createQuery(
            """
            SELECT c.content_hash, c.content
            FROM revision_binaries b
            JOIN asset_content c ON c.scope = b.scope AND c.content_hash = b.content_hash
            WHERE b.tenant_key = :t AND b.content_hash IN (<hashes>)
            """,
        )
            .bind("t", tenantKey)
            .bindList("hashes", pathsByHash.keys.toList())
            .map { rs, _ -> rs.getString("content_hash") to rs.getBytes("content") }
            .list()
            .toMap()

        val contents = LinkedHashMap<String, ByteArray>()
        for ((hash, paths) in pathsByHash) {
            val bytes = bytesByHash[hash] ?: continue
            paths.forEach { contents[it] = bytes }
        }
        return contents
    }

    private fun loadPayloads(handle: Handle, tenantKey: TenantKey, digests: List<String>): Map<String, JsonNode> {
        if (digests.isEmpty()) return emptyMap()
        return handle.createQuery("SELECT digest, payload::text AS payload FROM resource_revisions WHERE tenant_key = :t AND digest IN (<digests>)")
            .bind("t", tenantKey)
            .bindList("digests", digests.distinct())
            .map { rs, _ -> rs.getString("digest") to objectMapper.readTree(rs.getString("payload")) }
            .list()
            .toMap()
    }

    private fun loadManifestSnapshot(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String): CatalogManifest = handle
        .createQuery(
            """
            SELECT manifest_snapshot::text AS snapshot FROM catalog_releases
            WHERE tenant_key = :t AND catalog_key = :c AND version = :version
            """,
        )
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .bind("version", version)
        .map { rs, _ -> objectMapper.readValue(rs.getString("snapshot"), CatalogManifest::class.java) }
        .one()
}
