// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ImageResource
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.model.sha256Hex
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * The one field that stands in for a child revision inside a parent payload.
 *
 * An object carrying only this key is a reference, never content: nothing in the protocol is a
 * one-field object named this, so assembling a payload back is an unambiguous substitution.
 */
const val REVISION_REF_FIELD = "revisionDigest"

/**
 * Revision kinds, matching the `resource_revision_kinds` rows. A resource's kind is its wire type.
 *
 * The child kind takes the name of the field it is lifted out of, so [REVISION_REF_FIELD] and the
 * row it points at agree. It says nothing about ownership, and should not be read as doing so: a
 * model belongs to a template *version*, which belongs to a variant, which belongs to a template,
 * and a content-addressed row has no owner at all — two variants with the same model share one.
 */
const val TEMPLATE_MODEL_KIND = "templateModel"

/**
 * Retains the content of a released catalog as immutable, content-addressed revisions.
 *
 * Sole owner of the `resource_revisions` / `revision_binaries` SQL, the way
 * `CatalogUpstreamCheckStore` owns the upstream-check SQL. It is handed the open release
 * transaction, so a release and the content it retains commit together or not at all.
 *
 * **Deduplicated by digest within a tenant.** Re-releasing a catalog whose only edit is one
 * template writes one payload and reuses the rest; releasing content byte-identical to a previous
 * release writes nothing at all.
 *
 * **A template is a tree** (ADR 0026 §3a): the template payload carries a [REVISION_REF_FIELD]
 * reference in place of each variant's model, and each model is its own revision. A bundled
 * template is 20–70 KB of JSON, so keeping every variant in one payload would rewrite all of them
 * whenever one changed, defeat deduplication, and load the lot to render one variant.
 *
 * The digest is **not** the catalog fingerprint. It is internal storage identity, computed over the
 * stored payload; the fingerprint is computed by the contract over the wire form and is what
 * appears on the wire (ADR 0026 §5). Neither may be derived from the other.
 */
@Component
class ResourceRevisionStore(
    private val objectMapper: ObjectMapper,
) {

    /**
     * Writes a revision for every resource in [content], and the binary references that keep its
     * bytes from being collected.
     *
     * @return each resource's revision digest, keyed `"type/slug"` as the catalog content keys it.
     */
    fun retain(handle: Handle, tenantKey: TenantKey, content: CatalogContent): Map<String, String> {
        val scopes = resolveBinaryScopes(handle, tenantKey, content.resourceDetails.values.flatMap { contentHashes(it.resource) })

        return content.resourceDetails.mapValues { (_, detail) ->
            val resource = detail.resource
            val payload = payloadOf(handle, tenantKey, resource)
            val digest = write(handle, tenantKey, resource.type, payload)
            for (hash in contentHashes(resource)) {
                val scope = scopes[hash] ?: continue
                writeBinary(handle, tenantKey, digest, scope, hash)
            }
            digest
        }
    }

    /**
     * The resource as stored: its protocol form, with a template's models lifted into revisions of
     * their own and replaced by references. Writing the children here is what makes the parent
     * digest cover them.
     */
    private fun payloadOf(handle: Handle, tenantKey: TenantKey, resource: CatalogResource): JsonNode {
        val node = objectMapper.valueToTree<JsonNode>(resource)
        if (resource.type != "template" || node !is ObjectNode) return node

        liftModel(handle, tenantKey, node)
        (node.get("variants") as? ArrayNode)?.forEach { variant ->
            if (variant is ObjectNode) liftModel(handle, tenantKey, variant)
        }
        return node
    }

    /** Replaces one `templateModel` in place with a reference to the revision holding it. */
    private fun liftModel(handle: Handle, tenantKey: TenantKey, owner: ObjectNode) {
        val model = owner.get("templateModel")?.takeUnless { it.isNull } ?: return
        val digest = write(handle, tenantKey, TEMPLATE_MODEL_KIND, model)
        owner.set("templateModel", objectMapper.createObjectNode().put(REVISION_REF_FIELD, digest))
    }

    /**
     * Writes one revision and returns its digest.
     *
     * The digest is taken over the canonical serialisation, not over whatever key order the mapper
     * produced, so the same content digests the same however it was built — and JSONB does not
     * preserve key order anyway, so the stored bytes could never serve as the identity.
     */
    private fun write(handle: Handle, tenantKey: TenantKey, kind: String, payload: JsonNode): String {
        val canonical = objectMapper.writeValueAsString(sortKeys(payload))
        val digest = sha256Hex(canonical.toByteArray())
        handle.createUpdate(
            """
            INSERT INTO resource_revisions (tenant_key, digest, kind, payload)
            VALUES (:t, :digest, :kind, CAST(:payload AS JSONB))
            ON CONFLICT (tenant_key, digest) DO NOTHING
            """,
        )
            .bind("t", tenantKey)
            .bind("digest", digest)
            .bind("kind", kind)
            .bind("payload", canonical)
            .execute()
        return digest
    }

    /**
     * Deletes every revision of this tenant that no release reaches any more, and returns how many.
     *
     * Called wherever release entries stop existing — forgetting one release's content, and deleting
     * a catalog, which cascades its releases away. Without it those revisions are unreachable and
     * permanent: nothing else ever deletes one, and `revision_binaries` would go on holding the
     * bytes they named against the content sweep, so an image deleted from a deleted catalog could
     * never be reclaimed.
     *
     * **Reachability is a graph, not a lookup.** A release entry names a resource's revision; a
     * template's revision names its models through [REVISION_REF_FIELD], so a model is reachable
     * only through its parent. Deleting what no `release_entries` row names directly would take
     * every model with it. The closure below walks the refs out of the payloads, at any depth, so a
     * deeper tree costs a round trip rather than correctness.
     *
     * Deliberately not a scheduled sweep. Revisions become unreachable at exactly two moments, both
     * of them commands, and collecting inside the transaction that caused it means there is never a
     * window in which the database holds content nothing can reach. `ContentReaper` still owns the
     * blobs: dropping the last `revision_binaries` row only makes them collectable.
     */
    fun collectUnreferenced(handle: Handle, tenantKey: TenantKey): Int {
        val reachable = mutableSetOf<String>()
        var frontier = handle.createQuery(
            "SELECT DISTINCT revision_digest FROM release_entries WHERE tenant_key = :t",
        )
            .bind("t", tenantKey)
            .mapTo(String::class.java)
            .set()

        while (frontier.isNotEmpty()) {
            reachable += frontier
            frontier = childrenOf(handle, tenantKey, frontier) - reachable
        }

        return handle.createUpdate(
            """
            DELETE FROM resource_revisions
            WHERE tenant_key = :t AND NOT (digest = ANY(:reachable))
            """,
        )
            .bind("t", tenantKey)
            .bindArray("reachable", String::class.java, reachable.toTypedArray())
            .execute()
    }

    /**
     * The revisions these payloads reference, wherever they sit in the JSON.
     *
     * `$.**` is PostgreSQL's recursive jsonpath wildcard, so this finds a reference nested at any
     * depth without the store having to know the shape of a payload — which is the point, because
     * the shape is the contract's and changes without this code.
     */
    private fun childrenOf(handle: Handle, tenantKey: TenantKey, digests: Set<String>): Set<String> = handle.createQuery(
        """
        SELECT DISTINCT jsonb_array_elements_text(
                   jsonb_path_query_array(payload, '$.**.$REVISION_REF_FIELD')
               ) AS child
        FROM resource_revisions
        WHERE tenant_key = :t AND digest = ANY(:digests)
        """,
    )
        .bind("t", tenantKey)
        .bindArray("digests", String::class.java, digests.toTypedArray())
        .mapTo(String::class.java)
        .set()

    private fun writeBinary(handle: Handle, tenantKey: TenantKey, digest: String, scope: String, contentHash: String) {
        handle.createUpdate(
            """
            INSERT INTO revision_binaries (tenant_key, digest, scope, content_hash)
            VALUES (:t, :digest, :scope, :hash)
            ON CONFLICT DO NOTHING
            """,
        )
            .bind("t", tenantKey)
            .bind("digest", digest)
            .bind("scope", scope)
            .bind("hash", contentHash)
            .execute()
    }

    /** Every binary a resource needs: an image's own, and each of a font family's faces. */
    private fun contentHashes(resource: CatalogResource): List<String> = when (resource) {
        is ImageResource -> listOf(resource.contentHash)
        is FontResource -> resource.variants.map { it.contentHash }
        else -> emptyList()
    }

    /**
     * Which dedup scope each hash's bytes live under, derived from the owning asset's `sensitive`
     * flag exactly as at write time — the one rule `assetContentScope` and the content reaper use.
     * A hash with no asset row resolves to nothing and is skipped: there are no bytes to hold.
     */
    private fun resolveBinaryScopes(handle: Handle, tenantKey: TenantKey, hashes: List<String>): Map<String, String> {
        if (hashes.isEmpty()) return emptyMap()
        return handle.createQuery(
            """
            SELECT DISTINCT content_hash,
                   CASE WHEN sensitive THEN tenant_key::text ELSE 'global' END AS scope
            FROM assets
            WHERE tenant_key = :t AND content_hash IN (<hashes>)
            """,
        )
            .bind("t", tenantKey)
            .bindList("hashes", hashes.distinct())
            .map { rs, _ -> rs.getString("content_hash") to rs.getString("scope") }
            .list()
            .toMap()
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
