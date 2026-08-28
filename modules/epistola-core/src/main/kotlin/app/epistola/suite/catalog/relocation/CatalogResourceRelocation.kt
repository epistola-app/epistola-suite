// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.graph.ResourceReferenceSites
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.transaction.TransactionIsolationLevel
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.util.UUID

data class ResourceMoveBlocker(
    val code: String,
    val message: String,
)

data class CatalogResourceMovePreview(
    val source: ResourceAddress,
    val target: ResourceAddress,
    val resourceId: UUID?,
    val mutableRewriteCount: Int,
    val immutableReferenceCount: Int,
    val blockers: List<ResourceMoveBlocker>,
    val planFingerprint: String,
) {
    val executable: Boolean get() = blockers.isEmpty()
}

data class PreviewCatalogResourceMove(
    override val tenantKey: TenantKey,
    val source: ResourceAddress,
    val targetCatalogKey: CatalogKey,
) : Query<CatalogResourceMovePreview>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

data class MoveCatalogResource(
    override val tenantKey: TenantKey,
    val source: ResourceAddress,
    val targetCatalogKey: CatalogKey,
    val expectedPlanFingerprint: String,
) : Command<CatalogResourceMovePreview>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_MANAGE
}

class CatalogResourceMoveBlockedException(
    val blockers: List<ResourceMoveBlocker>,
) : IllegalStateException(blockers.joinToString("; ") { it.message })

class StaleCatalogResourceMovePlanException : IllegalStateException("The catalog resource move plan is stale; preview it again")

@Component
class PreviewCatalogResourceMoveHandler(
    private val jdbi: Jdbi,
    private val planner: CatalogResourceMovePlanner,
) : QueryHandler<PreviewCatalogResourceMove, CatalogResourceMovePreview> {
    override fun handle(query: PreviewCatalogResourceMove): CatalogResourceMovePreview = jdbi.inTransaction<CatalogResourceMovePreview, Exception>(TransactionIsolationLevel.REPEATABLE_READ) { handle ->
        planner.build(handle, query.tenantKey, query.source, query.targetCatalogKey).preview
    }
}

@Component
class MoveCatalogResourceHandler(
    private val jdbi: Jdbi,
    private val planner: CatalogResourceMovePlanner,
) : CommandHandler<MoveCatalogResource, CatalogResourceMovePreview> {
    override fun handle(command: MoveCatalogResource): CatalogResourceMovePreview = jdbi.inTransaction<CatalogResourceMovePreview, Exception> { handle ->
        handle.createQuery("SELECT pg_advisory_xact_lock(hashtextextended(:tenantKey, 0))")
            .bind("tenantKey", command.tenantKey.value)
            .map { _, _ -> Unit }
            .one()

        val plan = planner.build(handle, command.tenantKey, command.source, command.targetCatalogKey)
        if (plan.preview.planFingerprint != command.expectedPlanFingerprint) {
            throw StaleCatalogResourceMovePlanException()
        }
        if (!plan.preview.executable) throw CatalogResourceMoveBlockedException(plan.preview.blockers)

        for (rewrite in plan.rewrites) {
            val changed = when (rewrite) {
                is JsonRewrite.TemplateVersion -> handle.createUpdate(
                    """
                        UPDATE template_versions
                        SET template_model = :replacement::jsonb
                        WHERE tenant_key = :tenantKey
                          AND catalog_key = :catalogKey
                          AND template_key = :templateKey
                          AND variant_key = :variantKey
                          AND id = :version
                          AND status = 'draft'
                          AND template_model = :expected::jsonb
                        """,
                )
                    .bind("tenantKey", command.tenantKey)
                    .bind("catalogKey", rewrite.catalogKey)
                    .bind("templateKey", rewrite.ownerKey)
                    .bind("variantKey", rewrite.variantKey)
                    .bind("version", rewrite.version)
                    .bind("replacement", rewrite.replacement)
                    .bind("expected", rewrite.expected)
                    .execute()

                is JsonRewrite.VariantAttributes -> handle.createUpdate(
                    """
                        UPDATE template_variants
                        SET attributes = :replacement::jsonb
                        WHERE tenant_key = :tenantKey
                          AND catalog_key = :catalogKey
                          AND template_key = :templateKey
                          AND id = :variantKey
                          AND attributes = :expected::jsonb
                        """,
                )
                    .bind("tenantKey", command.tenantKey)
                    .bind("catalogKey", rewrite.catalogKey)
                    .bind("templateKey", rewrite.ownerKey)
                    .bind("variantKey", rewrite.variantKey)
                    .bind("replacement", rewrite.replacement)
                    .bind("expected", rewrite.expected)
                    .execute()

                is JsonRewrite.StencilVersion -> handle.createUpdate(
                    """
                        UPDATE stencil_versions
                        SET content = :replacement::jsonb
                        WHERE tenant_key = :tenantKey
                          AND catalog_key = :catalogKey
                          AND stencil_key = :stencilKey
                          AND id = :version
                          AND status = 'draft'
                          AND content = :expected::jsonb
                        """,
                )
                    .bind("tenantKey", command.tenantKey)
                    .bind("catalogKey", rewrite.catalogKey)
                    .bind("stencilKey", rewrite.ownerKey)
                    .bind("version", rewrite.version)
                    .bind("replacement", rewrite.replacement)
                    .bind("expected", rewrite.expected)
                    .execute()
            }
            if (changed != 1) throw StaleCatalogResourceMovePlanException()
        }

        val resourceId = requireNotNull(plan.preview.resourceId)

        // The address being vacated keeps resolving to this resource. If an earlier occupant of the
        // same address already left an alias behind, the most recent occupant wins -- an alias
        // always points at the resource that last held the address.
        handle.createUpdate(
            """
                INSERT INTO catalog_resource_aliases (
                    tenant_key, resource_type, catalog_key, resource_key, target_resource_id
                ) VALUES (
                    :tenantKey, :resourceType, :sourceCatalogKey, :resourceKey, :resourceId
                )
                ON CONFLICT (tenant_key, resource_type, catalog_key, resource_key) DO UPDATE
                SET target_resource_id = EXCLUDED.target_resource_id
                """,
        )
            .bind("tenantKey", command.tenantKey)
            .bind("resourceType", command.source.type.wireName)
            .bind("sourceCatalogKey", command.source.catalogKey)
            .bind("resourceKey", command.source.key)
            .bind("resourceId", resourceId)
            .execute()

        // Moving back to an address this resource previously held reclaims it canonically, so the
        // alias it left there last time is now redundant.
        handle.createUpdate(
            """
                DELETE FROM catalog_resource_aliases
                WHERE tenant_key = :tenantKey
                  AND resource_type = :resourceType
                  AND catalog_key = :targetCatalogKey
                  AND resource_key = :resourceKey
                  AND target_resource_id = :resourceId
                """,
        )
            .bind("tenantKey", command.tenantKey)
            .bind("resourceType", command.source.type.wireName)
            .bind("targetCatalogKey", command.targetCatalogKey)
            .bind("resourceKey", command.source.key)
            .bind("resourceId", resourceId)
            .execute()

        // The table comes from MovableResource, never from caller input. A type only appears there
        // once its table is keyed by identity, so this update cannot strand a dependant.
        val movable = requireNotNull(MovableResource.of(command.source.type))
        val moved = handle.createUpdate(
            """
                UPDATE ${movable.table}
                SET catalog_key = :targetCatalogKey
                WHERE tenant_key = :tenantKey
                  AND resource_id = :resourceId
                  AND catalog_key = :sourceCatalogKey
                """,
        )
            .bind("tenantKey", command.tenantKey)
            .bind("resourceId", resourceId)
            .bind("sourceCatalogKey", command.source.catalogKey)
            .bind("targetCatalogKey", command.targetCatalogKey)
            .execute()
        if (moved != 1) throw StaleCatalogResourceMovePlanException()
        // Owned hierarchies follow their parent's address by database rule, not by a statement here
        // -- stencil_versions via fk_stencil_versions_parent_address ON UPDATE CASCADE. Types with
        // no owned hierarchy, such as attributes, need nothing.

        plan.preview
    }
}

@Component
class CatalogResourceMovePlanner(
    private val objectMapper: ObjectMapper,
) {
    internal fun build(
        handle: Handle,
        tenantKey: TenantKey,
        source: ResourceAddress,
        targetCatalogKey: CatalogKey,
    ): CatalogResourceMovePlan {
        val target = source.copy(catalogKey = targetCatalogKey.value)
        val blockers = mutableListOf<ResourceMoveBlocker>()
        if (source.catalogKey == target.catalogKey) blockers += blocker("same-catalog", "Source and target catalogs must differ")
        val movable = MovableResource.of(source.type)
        if (movable == null) {
            blockers += blocker(
                "unsupported-resource-type",
                "${source.type.wireName} is not relocatable yet; its table is still keyed by address",
            )
        }

        val catalogs = handle.createQuery(
            """
            SELECT id::text, type::text
            FROM catalogs
            WHERE tenant_key = :tenantKey AND id IN (:sourceCatalogKey, :targetCatalogKey)
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("sourceCatalogKey", source.catalogKey)
            .bind("targetCatalogKey", target.catalogKey)
            .map { rs, _ -> rs.getString("id") to rs.getString("type") }
            .list()
            .toMap()
        if (catalogs[source.catalogKey] != "AUTHORED") blockers += blocker("source-read-only", "The source catalog must be authored and editable")
        if (catalogs[target.catalogKey] != "AUTHORED") blockers += blocker("target-read-only", "The target catalog must be authored and editable")

        val resourceId = handle.createQuery(
            """
            SELECT resource_id
            FROM catalog_resources
            WHERE tenant_key = :tenantKey
              AND resource_type = :resourceType
              AND catalog_key = :catalogKey
              AND resource_key = :resourceKey
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("resourceType", source.type.wireName)
            .bind("catalogKey", source.catalogKey)
            .bind("resourceKey", source.key)
            .mapTo(UUID::class.java)
            .findOne()
            .orElse(null)
        if (resourceId == null) blockers += blocker("resource-not-found", "The source address is not a canonical resource")

        val targetOccupied = handle.createQuery(
            """
            SELECT EXISTS(
                SELECT 1 FROM catalog_resources
                WHERE tenant_key = :tenantKey AND resource_type = :resourceType
                  AND catalog_key = :catalogKey AND resource_key = :resourceKey
                UNION ALL
                -- An alias this very resource left behind does not occupy the address:
                -- moving back to a previously-held catalog is a supported undo.
                SELECT 1 FROM catalog_resource_aliases
                WHERE tenant_key = :tenantKey AND resource_type = :resourceType
                  AND catalog_key = :catalogKey AND resource_key = :resourceKey
                  AND target_resource_id IS DISTINCT FROM :resourceId
            )
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("resourceType", source.type.wireName)
            .bind("catalogKey", target.catalogKey)
            .bind("resourceKey", target.key)
            .bind("resourceId", resourceId)
            .mapTo(Boolean::class.java)
            .one()
        if (targetOccupied) blockers += blocker("target-occupied", "The target address is already a resource or retained alias")

        val released = handle.createQuery(
            """
            SELECT EXISTS(
                SELECT 1 FROM catalog_releases
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
            )
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("catalogKey", source.catalogKey)
            .mapTo(Boolean::class.java)
            .one()
        if (released) blockers += blocker("released-resource", "Released resources require a portable subscriber relocation handoff")

        val rewrites = mutableListOf<JsonRewrite>()
        var immutableReferences = 0
        val templateVersions = loadTemplateVersions(handle, tenantKey)
        val stencilVersions = loadStencilVersions(handle, tenantKey)
        if (movable != null && resourceId != null && movable.contentReferenceKinds.isNotEmpty()) {
            templateVersions.forEach { row ->
                val rewritten = rewriteIncomingContentReferences(row.json, row.catalogKey, source, target.catalogKey, movable)
                if (rewritten.changed) {
                    if (row.status == "draft") {
                        rewrites += JsonRewrite.TemplateVersion(row.catalogKey, row.ownerKey, row.variantKey!!, row.version, row.rawJson, rewritten.json.toString())
                    } else {
                        immutableReferences++
                    }
                }
            }
            stencilVersions.forEach { row ->
                var rewritten = rewriteIncomingContentReferences(row.json, row.catalogKey, source, target.catalogKey, movable)
                // Rows here are stencil_versions, so ownerKey is a stencil key. The type check is a
                // correctness guard, not leftover specialisation: another type's key could coincide
                // with a stencil key and wrongly select that stencil's own versions.
                if (source.type == CatalogResourceType.STENCIL && row.ownerKey == source.key && row.catalogKey == source.catalogKey) {
                    if (row.status == "draft") {
                        rewritten = qualifyRelativeOutgoingReferences(rewritten.json, source.catalogKey, rewritten.changed)
                    } else if (containsRelativeOutgoingReference(rewritten.json)) {
                        blockers += blocker(
                            "immutable-relative-reference",
                            "Stencil version ${row.version} has a relative dependency whose meaning would change after the move",
                        )
                    }
                }
                if (rewritten.changed && row.status == "draft") {
                    rewrites += JsonRewrite.StencilVersion(row.catalogKey, row.ownerKey, row.version, row.rawJson, rewritten.json.toString())
                } else if (rewritten.changed) {
                    immutableReferences++
                }
            }
        }

        if (movable == MovableResource.ATTRIBUTE && resourceId != null) {
            rewrites += attributeKeyRewrites(handle, tenantKey, source, target.catalogKey)
        }

        val fingerprint = fingerprint(source, target, resourceId, blockers, rewrites, immutableReferences)
        return CatalogResourceMovePlan(
            preview = CatalogResourceMovePreview(source, target, resourceId, rewrites.size, immutableReferences, blockers.distinct(), fingerprint),
            rewrites = rewrites,
        )
    }

    /**
     * Attributes are referenced by the *keys* of `template_variants.attributes`, either qualified as
     * `catalog.attribute` or left bare. A bare key resolves tenant-globally, so a move does not
     * affect it; only an explicitly qualified key names the catalog being vacated.
     *
     * `template_variants` is live mutable configuration rather than versioned content, so every such
     * reference is rewritable and none has to survive on an alias.
     */
    private fun attributeKeyRewrites(
        handle: Handle,
        tenantKey: TenantKey,
        source: ResourceAddress,
        targetCatalog: String,
    ): List<JsonRewrite> {
        val oldKey = source.catalogKey + "." + source.key
        val newKey = targetCatalog + "." + source.key
        return handle.createQuery(
            """
            SELECT catalog_key::text, template_key::text, id::text variant_key, attributes::text
            FROM template_variants
            WHERE tenant_key = :tenantKey AND attributes ?? :oldKey
            ORDER BY catalog_key, template_key, id
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("oldKey", oldKey)
            .map { rs, _ ->
                val raw = rs.getString("attributes")
                val moved = (objectMapper.readTree(raw) as ObjectNode).deepCopy()
                moved.set(newKey, moved.remove(oldKey))
                JsonRewrite.VariantAttributes(
                    rs.getString("catalog_key"),
                    rs.getString("template_key"),
                    rs.getString("variant_key"),
                    raw,
                    moved.toString(),
                )
            }
            .list()
    }

    private fun loadTemplateVersions(handle: Handle, tenantKey: TenantKey): List<JsonOwnerRow> = handle.createQuery(
        """
        SELECT catalog_key::text, template_key::text owner_key, variant_key::text, id, status, template_model::text json
        FROM template_versions
        WHERE tenant_key = :tenantKey
        ORDER BY catalog_key, template_key, variant_key, id
        """,
    )
        .bind("tenantKey", tenantKey)
        .map { rs, _ ->
            val raw = rs.getString("json")
            JsonOwnerRow(rs.getString("catalog_key"), rs.getString("owner_key"), rs.getString("variant_key"), rs.getInt("id"), rs.getString("status"), raw, objectMapper.readTree(raw))
        }.list()

    private fun loadStencilVersions(handle: Handle, tenantKey: TenantKey): List<JsonOwnerRow> = handle.createQuery(
        """
        SELECT catalog_key::text, stencil_key::text owner_key, id, status, content::text json
        FROM stencil_versions
        WHERE tenant_key = :tenantKey
        ORDER BY catalog_key, stencil_key, id
        """,
    )
        .bind("tenantKey", tenantKey)
        .map { rs, _ ->
            val raw = rs.getString("json")
            JsonOwnerRow(rs.getString("catalog_key"), rs.getString("owner_key"), null, rs.getInt("id"), rs.getString("status"), raw, objectMapper.readTree(raw))
        }.list()

    /** Points content references to the moving resource at its destination catalog. */
    private fun rewriteIncomingContentReferences(
        root: JsonNode,
        ownerCatalog: String,
        source: ResourceAddress,
        targetCatalog: String,
        movable: MovableResource,
    ): RewriteResult {
        val copy = root.deepCopy()
        var changed = false
        for (site in ResourceReferenceSites.scan(copy)) {
            if (site.kind !in movable.contentReferenceKinds || site.key != source.key) continue
            val explicitCatalog = site.catalogKey
            if (explicitCatalog == source.catalogKey || (explicitCatalog == null && ownerCatalog == source.catalogKey)) {
                site.setCatalogKey(targetCatalog)
                changed = true
            }
        }
        return RewriteResult(copy, changed)
    }

    /**
     * Pins the moving stencil's own unqualified dependencies to the catalog they resolve against
     * today, so they keep their meaning once the stencil resolves relative to its destination.
     *
     * Content written since references became qualified on write has nothing left to pin, so this
     * only does work for drafts predating that rule. Its published counterpart is the
     * `immutable-relative-reference` blocker, which cannot rewrite and so refuses the move.
     */
    private fun qualifyRelativeOutgoingReferences(root: JsonNode, sourceCatalog: String, alreadyChanged: Boolean): RewriteResult {
        var changed = alreadyChanged
        for (site in relativeReferences(root)) {
            site.setCatalogKey(sourceCatalog)
            changed = true
        }
        return RewriteResult(root, changed)
    }

    private fun containsRelativeOutgoingReference(root: JsonNode): Boolean = relativeReferences(root).isNotEmpty()

    private fun relativeReferences(root: JsonNode) = ResourceReferenceSites.scan(root)
        .filter { it.kind.relativeWhenUnqualified && it.catalogKey == null }

    /**
     * Covers exactly what the operator approved in the preview, not the state of the tenant.
     *
     * Correctness comes from re-planning under the advisory lock in [MoveCatalogResourceHandler]
     * plus the per-statement `= :expected::jsonb` guard on each rewrite. This fingerprint is the
     * consent check on top of that: a reference appearing or disappearing changes [rewrites] or
     * [immutableReferences] and so invalidates the plan, while an unrelated edit elsewhere in the
     * tenant leaves it untouched.
     */
    private fun fingerprint(
        source: ResourceAddress,
        target: ResourceAddress,
        resourceId: UUID?,
        blockers: List<ResourceMoveBlocker>,
        rewrites: List<JsonRewrite>,
        immutableReferences: Int,
    ): String {
        val input = buildString {
            appendLine(source.id)
            appendLine(target.id)
            appendLine(resourceId)
            appendLine(immutableReferences)
            blockers.sortedBy { it.code + it.message }.forEach { appendLine("${it.code}:${it.message}") }
            rewrites.sortedBy(JsonRewrite::identity).forEach { appendLine("${it.identity}:${it.expected}:${it.replacement}") }
        }
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun blocker(code: String, message: String) = ResourceMoveBlocker(code, message)

    private data class JsonOwnerRow(
        val catalogKey: String,
        val ownerKey: String,
        val variantKey: String?,
        val version: Int,
        val status: String,
        val rawJson: String,
        val json: JsonNode,
    )

    private data class RewriteResult(val json: JsonNode, val changed: Boolean)
}

internal data class CatalogResourceMovePlan(
    val preview: CatalogResourceMovePreview,
    val rewrites: List<JsonRewrite>,
)

internal sealed interface JsonRewrite {
    val catalogKey: String
    val ownerKey: String
    val version: Int
    val expected: String
    val replacement: String
    val identity: String

    data class TemplateVersion(
        override val catalogKey: String,
        override val ownerKey: String,
        val variantKey: String,
        override val version: Int,
        override val expected: String,
        override val replacement: String,
    ) : JsonRewrite {
        override val identity get() = "template:$catalogKey:$ownerKey:$variantKey:$version"
    }

    data class StencilVersion(
        override val catalogKey: String,
        override val ownerKey: String,
        override val version: Int,
        override val expected: String,
        override val replacement: String,
    ) : JsonRewrite {
        override val identity get() = "stencil:$catalogKey:$ownerKey:$version"
    }

    /** A variant's attribute map is unversioned live configuration, so [version] is unused. */
    data class VariantAttributes(
        override val catalogKey: String,
        override val ownerKey: String,
        val variantKey: String,
        override val expected: String,
        override val replacement: String,
    ) : JsonRewrite {
        override val version = 0
        override val identity get() = "variant-attributes:$catalogKey:$ownerKey:$variantKey"
    }
}
