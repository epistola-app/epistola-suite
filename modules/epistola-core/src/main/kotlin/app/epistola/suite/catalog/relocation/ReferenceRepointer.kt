// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.revisions.ContentReference
import app.epistola.suite.catalog.revisions.ContentReferences
import app.epistola.suite.catalog.revisions.DraftResources
import app.epistola.suite.catalog.revisions.ResourceContentStore
import app.epistola.suite.catalog.types.ResourceTypes
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * One row of a draft a relocation changes: the content it names now ([row]) and the content it is to
 * name ([payload]), with the relocation that caused it.
 */
data class RowRewrite(
    val row: DraftResources.Row,
    val payload: ObjectNode,
    /** The content hash [payload] is stored under. */
    val contentHash: String,
    /** Which relocation of the batch it follows; null when it could not be attributed. */
    val attributedTo: ResourceAddress?,
    /** Whether a reference in it changed, beside its key or its catalog. */
    val repointed: Boolean = false,
) {
    /** The row's catalog, in [tenantKey]. */
    fun catalogIn(tenantKey: TenantKey): CatalogId = catalogAt(row.catalogAddress, tenantKey)

    /** What it is, for the plan's fingerprint and the order it is applied in. */
    val identity: String get() = "${row.catalogAddress}:${row.parentKey?.let { "template/$it/" }.orEmpty()}${row.type}/${row.key}"
}

/** A resource the batch moves: its own row and its variants' rows, as the target is to hold them. */
data class RowMove(
    val relocation: ResourceRelocation,
    val head: RowRewrite,
    val parts: List<RowRewrite>,
)

/** What re-pointing a batch takes: the rows that name what moves, and the moving resources' own rows. */
data class RepointPlan(val rewrites: List<RowRewrite>, val moves: List<RowMove>)

/**
 * Re-points references for a relocation, generically (ADR 0042): every type's content is stored as
 * its wire form with its references indexed (ADR 0033, ADR 0040), so what names a moving resource is
 * found in `resource_references`, its references are re-pointed where [ContentReferences.repoint]
 * finds them (the reference sites and what a type carries itself, [app.epistola.suite.catalog.types.ResourceType.repoint]),
 * and the row is pointed at the content that results. No type is named here.
 *
 * Every reference is resolved against the catalog of the content holding it, then expressed against
 * the catalog that content is in afterwards, as the stores keep it: one into its own catalog relative,
 * one into another by that catalog's key, the alias this installation declares every dependency
 * under (ADR 0034). So a reference to a resource that moves follows it, and a moving resource's
 * references to what stays behind name the catalog it leaves. Two kinds resolve tenant-wide rather
 * than in a catalog, and keep that: an image named without a catalog, and an attribute named without
 * one (a variant's bare key), which follows a rename only when it is the one attribute of that key.
 */
@Component
class ReferenceRepointer(
    private val objectMapper: ObjectMapper,
    private val types: ResourceTypes,
    private val contents: ResourceContentStore,
) {
    /**
     * The rows [relocations] change, in the drafts each catalog's statements mean, with [aliases]
     * (`catalog address` and alias to the catalog it names) resolving a reference into another catalog.
     */
    fun plan(handle: Handle, tenantKey: TenantKey, relocations: List<ResourceRelocation>, aliases: Map<Pair<String, String>, String>): RepointPlan {
        val moves = relocations.associate { it.source to it.target }
        val uniqueAttributeKeys = uniqueAttributeKeys(handle, tenantKey, relocations)
        fun rewrite(row: DraftResources.Row, holderCatalog: String, finalCatalog: String, newKey: String?): RowRewrite? {
            val payload = objectMapper.readTree(row.payload) as ObjectNode
            val resource = payload.get("resource") as? ObjectNode ?: payload
            var attributedTo: ResourceAddress? = null
            val repointed = ContentReferences.repoint(resource, types.find(row.kind)) { reference ->
                val moved = if (reference.type == CatalogResourceType.ATTRIBUTE.wireName) {
                    attributeFollowing(reference, relocations, uniqueAttributeKeys)
                } else {
                    following(reference, holderCatalog, finalCatalog, moves, aliases)
                }
                moved?.also { (_, cause) -> if (cause != null) attributedTo = attributedTo ?: cause }?.first
            }
            val renamed = newKey != null && resource.path("key").asString("") != newKey
            if (renamed) resource.put("key", newKey)
            if (!repointed && !renamed && holderCatalog == finalCatalog) return null
            return RowRewrite(row, payload, contents.hashOf(payload), attributedTo, repointed)
        }

        val moving = relocations.associateBy { it.source }
        val rewrites = DraftResources.referencing(handle, tenantKey, moves.keys.map { it.type.wireName to it.key })
            .filter { row -> ownerOf(row) !in moving.keys }
            .mapNotNull { row -> rewrite(row, row.catalogAddress, row.catalogAddress, newKey = null) }
        val rowMoves = relocations.mapNotNull { relocation ->
            val rows = DraftResources.rowsOf(handle, relocation.source.catalogIn(tenantKey), relocation.source.type.wireName, relocation.source.key)
            val head = rows.firstOrNull { it.parentKey == null } ?: return@mapNotNull null
            val source = relocation.source.catalogAddress
            val target = relocation.target.catalogAddress
            RowMove(
                relocation,
                // Always rewritten: its key is in its content, and the row is stored again in the target.
                rewrite(head, source, target, newKey = relocation.target.key)?.let { if (it.attributedTo == null) it.copy(attributedTo = relocation.source) else it }
                    ?: RowRewrite(head, objectMapper.readTree(head.payload) as ObjectNode, head.contentHash, relocation.source),
                rows.filter { it.parentKey != null }.map { part ->
                    rewrite(part, source, target, newKey = null)?.let { if (it.attributedTo == null) it.copy(attributedTo = relocation.source) else it }
                        ?: RowRewrite(part, objectMapper.readTree(part.payload) as ObjectNode, part.contentHash, null)
                },
            )
        }
        return RepointPlan(rewrites, rowMoves)
    }

    /** Stores what [rewrite] is to name and points its row at it; false when the row changed since it was read. */
    fun apply(handle: Handle, tenantKey: TenantKey, rewrite: RowRewrite, catalog: CatalogId = rewrite.catalogIn(tenantKey)): Boolean {
        if (rewrite.contentHash == rewrite.row.contentHash) return true
        contents.store(handle, tenantKey, rewrite.row.kind, rewrite.payload)
        return DraftResources.repoint(handle, catalog, rewrite.row, rewrite.contentHash)
    }

    /** Stores [rewrite]'s content and returns its hash, for a row moved rather than re-pointed in place. */
    fun store(handle: Handle, tenantKey: TenantKey, rewrite: RowRewrite): String = contents.store(handle, tenantKey, rewrite.row.kind, rewrite.payload)

    /**
     * [reference], held by content of [holderCatalog] that is in [finalCatalog] afterwards, as it is
     * to be written, and the relocation it follows; null when it stays as it is. One whose alias this
     * installation cannot resolve is left alone: it resolves nowhere now either.
     */
    private fun following(
        reference: ContentReference,
        holderCatalog: String,
        finalCatalog: String,
        moves: Map<ResourceAddress, ResourceAddress>,
        aliases: Map<Pair<String, String>, String>,
    ): Pair<ContentReference, ResourceAddress?>? {
        val type = CatalogResourceType.entries.firstOrNull { it.wireName == reference.type } ?: return null
        val own = holderCatalog.substringAfter('/')
        val referencedCatalog = when (reference.alias) {
            null, own -> holderCatalog
            else -> aliases[holderCatalog to reference.alias] ?: return null
        }
        val referenced = ResourceAddress.at(type, referencedCatalog, reference.key)
        val destination = moves[referenced] ?: referenced
        if (destination == referenced && holderCatalog == finalCatalog) return null
        val alias = destination.catalogKey.takeUnless { destination.catalogAddress == finalCatalog }
        if (alias == reference.alias && destination.key == reference.key) return null
        return ContentReference(alias, reference.type, destination.key) to referenced.takeIf { it in moves }
    }

    /**
     * An attribute [reference] as it is to be written, and the relocation it follows: a qualified one
     * (`catalog.attribute`) follows its attribute wherever it goes; a bare one resolves tenant-wide by
     * key, so it follows only a rename of the one attribute answering to that key, qualified with where
     * it went. Never made relative: a bare key means another thing.
     */
    private fun attributeFollowing(reference: ContentReference, relocations: List<ResourceRelocation>, unique: Set<String>): Pair<ContentReference, ResourceAddress?>? {
        val relocation = relocations.firstOrNull { (source, target) ->
            source.type == CatalogResourceType.ATTRIBUTE && source.key == reference.key &&
                if (reference.alias == null) source.key != target.key && source.key in unique else source.catalogKey == reference.alias
        } ?: return null
        return ContentReference(relocation.target.catalogKey, reference.type, relocation.target.key) to relocation.source
    }

    /** The keys of the attributes [relocations] rename that exactly one attribute of the tenant answers to. */
    private fun uniqueAttributeKeys(handle: Handle, tenantKey: TenantKey, relocations: List<ResourceRelocation>): Set<String> {
        val renamed = relocations.filter { it.source.type == CatalogResourceType.ATTRIBUTE && it.source.key != it.target.key }.map { it.source.key }
        if (renamed.isEmpty()) return emptySet()
        return handle.createQuery("SELECT id FROM current_attributes WHERE tenant_key = :t AND id IN (<keys>) GROUP BY id HAVING COUNT(*) = 1")
            .bind("t", tenantKey)
            .bindList("keys", renamed)
            .mapTo(String::class.java)
            .set()
    }

    private fun ownerOf(row: DraftResources.Row): ResourceAddress = row.parentKey
        ?.let { ResourceAddress.at(CatalogResourceType.TEMPLATE, row.catalogAddress, it) }
        ?: ResourceAddress.at(CatalogResourceType.entries.first { it.wireName == row.type }, row.catalogAddress, row.key)
}

/** The catalog at [address] (`namespace/key`) of [tenantKey]. */
internal fun catalogAt(address: String, tenantKey: TenantKey): CatalogId = CatalogId(address.substringBefore('/'), CatalogKey.of(address.substringAfter('/')), TenantId(tenantKey))
