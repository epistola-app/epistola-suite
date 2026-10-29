// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.catalog.protocol.CatalogResource
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.revisions.ContentReference
import app.epistola.suite.catalog.revisions.ContentReferences
import app.epistola.suite.catalog.types.ResourceTypes
import app.epistola.suite.common.ids.CatalogId
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/** Where a resource of the merge base went: the catalog it is in now, by the key that names it, and its key there. */
data class MergeMovedTo(val catalogKey: String, val key: String)

/**
 * The moves a merge deduces (ADR 0042): a resource the merge base holds and the draft does not, whose
 * identity the registry now has at another address, was moved, not removed. A release's rows keep
 * the identity they were cut with, so the merge base says which identity was at the old address;
 * nothing about the move is recorded, and in another installation, where identities are its own, a
 * move stays a removal and an addition.
 *
 * Before the merge classifies anything, the merge base and the release are rebased through the
 * moves ([rebase]): what they name at an old address names the new one, as the refactoring re-pointed
 * the draft's own content. A fix to a template that names a moved theme then reads as a fix, and is
 * taken already pointing at the theme where it went.
 */
@Component
class MergeMoves(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val types: ResourceTypes,
    private val inputs: MergeInputReader,
) {
    /** What moved out of [catalog] since [read]'s merge base, by `type/key`: where each went. */
    fun of(catalog: CatalogId, read: MergeInputs): Map<String, MergeMovedTo> {
        val mergeBase = read.mergeBase ?: return emptyMap()
        val gone = read.base.values.filter { (key, value) -> value is MergeValue.Resource && key !in read.ours.values }.keys
        if (gone.isEmpty()) return emptyMap()
        return jdbi.withHandle<Map<String, MergeMovedTo>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT r.resource_type || '/' || r.resource_key AS entry, now_catalog.catalog_key AS catalog_key,
                       now_catalog.catalog_id = r.catalog_id AS same_catalog, cr.resource_key AS now_key
                FROM release_wire_resources r
                JOIN catalog_versions v ON v.tenant_key = r.tenant_key AND v.version_id = r.version_id AND v.state = 'released' AND v.version = :mergeBase
                JOIN catalog_resources cr ON cr.tenant_key = r.tenant_key AND cr.resource_id = r.resource_id
                JOIN catalogs now_catalog ON now_catalog.tenant_key = cr.tenant_key AND now_catalog.catalog_id = cr.catalog_id
                WHERE r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")}
                  AND (cr.catalog_id <> r.catalog_id OR cr.resource_key <> r.resource_key)
                """,
            )
                .bind("t", catalog.tenantKey)
                .bindCatalog("c", catalog)
                .bind("mergeBase", mergeBase)
                .map { rs, _ -> rs.getString("entry") to MergeMovedTo(rs.getString("catalog_key"), rs.getString("now_key")) }
                .list()
                .filter { (entry, _) -> entry in gone }
                .toMap()
        }
    }

    /** [read] with its merge base and release naming each of [moves] where it went. */
    fun rebase(catalog: CatalogId, read: MergeInputs, moves: Map<String, MergeMovedTo>): MergeInputs {
        if (moves.isEmpty()) return read
        val own = catalog.key.value
        val to = { reference: ContentReference ->
            val alias = reference.alias?.takeUnless { it == own }
            moves["${reference.type}/${reference.key}"]?.takeIf { alias == null }?.let { ContentReference(it.catalogKey.takeUnless { key -> key == own }, reference.type, it.key) }
        }
        val base = read.base.content?.let { inputs.input(catalog.key, repointed(it, to)) } ?: read.base
        val theirs = read.theirs.content?.let { inputs.input(catalog.key, repointed(it, to)) } ?: read.theirs
        // What the draft held of the base it held as released; rebased, it holds the rebased base.
        val heldBase = read.heldBase.mapValues { (key, held) -> if (held == read.base.fingerprints[key]) base.fingerprints[key] ?: held else held }
        return read.copy(base = base, theirs = theirs, heldBase = heldBase)
    }

    private fun repointed(content: CatalogContent, to: (ContentReference) -> ContentReference?): CatalogContent = content.copy(
        resourceDetails = content.resourceDetails.mapValues { (_, detail) ->
            val resource = detail.resource
            val tree = objectMapper.valueToTree<ObjectNode>(resource)
            if (!ContentReferences.repoint(tree, types.of(resource), to)) return@mapValues detail
            detail.copy(resource = objectMapper.treeToValue(tree, resource.javaClass))
        },
    )
}
