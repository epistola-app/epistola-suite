// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
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
        handle.createUpdate(
            """
                INSERT INTO catalog_resource_aliases (
                    tenant_key, resource_type, catalog_key, resource_key, target_resource_id
                ) VALUES (
                    :tenantKey, :resourceType, :sourceCatalogKey, :resourceKey, :resourceId
                )
                """,
        )
            .bind("tenantKey", command.tenantKey)
            .bind("resourceType", command.source.type.wireName)
            .bind("sourceCatalogKey", command.source.catalogKey)
            .bind("resourceKey", command.source.key)
            .bind("resourceId", resourceId)
            .execute()

        val moved = handle.createUpdate(
            """
                UPDATE stencils
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

        handle.createUpdate(
            """
                UPDATE stencil_versions
                SET catalog_key = :targetCatalogKey
                WHERE tenant_key = :tenantKey AND stencil_resource_id = :resourceId
                """,
        )
            .bind("tenantKey", command.tenantKey)
            .bind("resourceId", resourceId)
            .bind("targetCatalogKey", command.targetCatalogKey)
            .execute()

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
        if (source.type != CatalogResourceType.STENCIL) {
            blockers += blocker("unsupported-resource-type", "The alpha currently supports stencil moves only")
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
                SELECT 1 FROM catalog_resource_aliases
                WHERE tenant_key = :tenantKey AND resource_type = :resourceType
                  AND catalog_key = :catalogKey AND resource_key = :resourceKey
            )
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("resourceType", source.type.wireName)
            .bind("catalogKey", target.catalogKey)
            .bind("resourceKey", target.key)
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
        if (source.type == CatalogResourceType.STENCIL && resourceId != null) {
            templateVersions.forEach { row ->
                val rewritten = rewriteIncomingStencilReferences(row.json, row.catalogKey, source, target.catalogKey)
                if (rewritten.changed) {
                    if (row.status == "draft") {
                        rewrites += JsonRewrite.TemplateVersion(row.catalogKey, row.ownerKey, row.variantKey!!, row.version, row.rawJson, rewritten.json.toString())
                    } else {
                        immutableReferences++
                    }
                }
            }
            stencilVersions.forEach { row ->
                var rewritten = rewriteIncomingStencilReferences(row.json, row.catalogKey, source, target.catalogKey)
                if (row.ownerKey == source.key && row.catalogKey == source.catalogKey) {
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

        val observedState = (templateVersions + stencilVersions).map { it.identity + ":" + it.status + ":" + it.rawJson }
        val fingerprint = fingerprint(source, target, resourceId, blockers, rewrites, immutableReferences, observedState)
        return CatalogResourceMovePlan(
            preview = CatalogResourceMovePreview(source, target, resourceId, rewrites.size, immutableReferences, blockers.distinct(), fingerprint),
            rewrites = rewrites,
        )
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

    private fun rewriteIncomingStencilReferences(
        root: JsonNode,
        ownerCatalog: String,
        source: ResourceAddress,
        targetCatalog: String,
    ): RewriteResult {
        val copy = root.deepCopy()
        var changed = false
        walkObjects(copy) { node ->
            if (node.path("type").textOrNull() != "stencil") return@walkObjects
            val props = node.path("props") as? ObjectNode ?: return@walkObjects
            if (props.path("stencilId").textOrNull() != source.key) return@walkObjects
            val explicitCatalog = props.path("catalogKey").textOrNull()
            if (explicitCatalog == source.catalogKey || (explicitCatalog == null && ownerCatalog == source.catalogKey)) {
                props.put("catalogKey", targetCatalog)
                changed = true
            }
        }
        return RewriteResult(copy, changed)
    }

    private fun qualifyRelativeOutgoingReferences(root: JsonNode, sourceCatalog: String, alreadyChanged: Boolean): RewriteResult {
        var changed = alreadyChanged
        walkObjects(root) { node ->
            if (node.path("type").textOrNull() == "stencil") {
                (node.path("props") as? ObjectNode)?.takeIf { !it.path("catalogKey").isString }?.let {
                    it.put("catalogKey", sourceCatalog)
                    changed = true
                }
            }
            for (field in listOf("themeRef", "fontFamily")) {
                val resourceKey = if (field == "themeRef") "themeId" else "slug"
                (node.path(field) as? ObjectNode)?.takeIf {
                    it.path(resourceKey).isString && !it.path("catalogKey").isString
                }?.let {
                    it.put("catalogKey", sourceCatalog)
                    changed = true
                }
            }
        }
        return RewriteResult(root, changed)
    }

    private fun containsRelativeOutgoingReference(root: JsonNode): Boolean {
        var found = false
        walkObjects(root) { node ->
            if (node.path("type").textOrNull() == "stencil" && node.path("props").path("stencilId").isString && !node.path("props").path("catalogKey").isString) found = true
            if (node.path("themeRef").path("themeId").isString && !node.path("themeRef").path("catalogKey").isString) found = true
            if (node.path("fontFamily").path("slug").isString && !node.path("fontFamily").path("catalogKey").isString) found = true
        }
        return found
    }

    private fun walkObjects(node: JsonNode, visit: (ObjectNode) -> Unit) {
        if (node is ObjectNode) {
            visit(node)
            node.properties().forEach { (_, child) -> walkObjects(child, visit) }
        } else if (node.isArray) {
            node.forEach { walkObjects(it, visit) }
        }
    }

    private fun JsonNode.textOrNull(): String? = takeIf { isString }?.stringValue()?.takeIf { it.isNotBlank() }

    private fun fingerprint(
        source: ResourceAddress,
        target: ResourceAddress,
        resourceId: UUID?,
        blockers: List<ResourceMoveBlocker>,
        rewrites: List<JsonRewrite>,
        immutableReferences: Int,
        observedState: List<String>,
    ): String {
        val input = buildString {
            appendLine(source.id)
            appendLine(target.id)
            appendLine(resourceId)
            appendLine(immutableReferences)
            blockers.sortedBy { it.code + it.message }.forEach { appendLine("${it.code}:${it.message}") }
            rewrites.sortedBy(JsonRewrite::identity).forEach { appendLine("${it.identity}:${it.expected}:${it.replacement}") }
            observedState.sorted().forEach(::appendLine)
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
    ) {
        val identity get() = listOf(catalogKey, ownerKey, variantKey.orEmpty(), version.toString()).joinToString(":")
    }

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
}
