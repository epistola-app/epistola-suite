// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.dependencies.MissingDependencyDeclarer
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.AuditDetailed
import app.epistola.suite.common.ids.ResourceIdentity
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.templateAtAddress
import app.epistola.suite.themes.ThemeReads
import app.epistola.suite.themes.fields
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.transaction.TransactionIsolationLevel
import org.springframework.stereotype.Component

data class ResourceMoveBlocker(
    val code: String,
    val message: String,
    /** The relocation this blocks, or null when it is a property of the batch as a whole. */
    val source: ResourceAddress? = null,
)

/**
 * Something the operator should know before applying, which does not stop the move.
 *
 * The distinction is whether the suite can still guarantee a correct result. A blocker means it
 * cannot -- the move would corrupt something or could not be applied at all. A warning means the
 * move is well-defined here but has a consequence beyond this installation, which only the
 * operator can judge.
 */
data class ResourceMoveWarning(
    val code: String,
    val message: String,
    /** The relocation this concerns, or null when it is a property of the batch as a whole. */
    val source: ResourceAddress? = null,
)

/**
 * One resource's destination: a full address, so a relocation can change the catalog, the key, or
 * both.
 *
 * Moving and renaming are the same operation — both change the address while identity stays put —
 * so separating them would mean two commands with the same rewriting and validation. Carrying the
 * key here also gives a collision somewhere to go: a resource whose key is already taken in the
 * destination can land under a different one, where a catalog-only move could only be blocked.
 */
data class ResourceRelocation(
    val source: ResourceAddress,
    val target: ResourceAddress,
) {
    init {
        require(source.type == target.type) { "A relocation cannot change a resource's type" }
    }
}

/** The common case: move a resource to another catalog, keeping its key. */
fun ResourceAddress.movedTo(catalogKey: CatalogKey) = ResourceRelocation(this, copy(catalogKey = catalogKey.value))

/** Move a resource to another catalog under a different key. */
fun ResourceAddress.movedTo(catalogKey: CatalogKey, key: String) = ResourceRelocation(this, copy(catalogKey = catalogKey.value, key = key))

/** Keep a resource where it is under a different key -- a rename. */
fun ResourceAddress.renamedTo(key: String) = ResourceRelocation(this, copy(key = key))

/** What one relocation in a batch does. */
data class ResourceRelocationPlan(
    val source: ResourceAddress,
    val target: ResourceAddress,
    val resourceId: ResourceIdentity?,
    val mutableRewriteCount: Int,
    val immutableReferenceCount: Int,
)

/**
 * The batch as a whole.
 *
 * A batch is all-or-nothing: one transaction, one fingerprint, and any blocker stops every member.
 * Partial application would leave a half-reorganised tenant with no record of what was intended,
 * and the cycle check is only meaningful for the whole set anyway — moving several resources
 * together is precisely how an author resolves a cycle that any single move would be blocked on.
 */
data class CatalogResourceMovePreview(
    val relocations: List<ResourceRelocationPlan>,
    val blockers: List<ResourceMoveBlocker>,
    val warnings: List<ResourceMoveWarning>,
    val planFingerprint: String,
) {
    val executable: Boolean get() = blockers.isEmpty()
    val mutableRewriteCount: Int get() = relocations.sumOf { it.mutableRewriteCount }
    val immutableReferenceCount: Int get() = relocations.sumOf { it.immutableReferenceCount }
}

data class PreviewCatalogResourceMove(
    override val tenantKey: TenantKey,
    val relocations: List<ResourceRelocation>,
) : Query<CatalogResourceMovePreview>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

data class MoveCatalogResources(
    override val tenantKey: TenantKey,
    val relocations: List<ResourceRelocation>,
    val expectedPlanFingerprint: String,
) : Command<CatalogResourceMovePreview>,
    RequiresPermission,
    AuditDetailed {
    override val permission get() = Permission.CATALOG_MANAGE

    /** Each resource's old address and the one it moved to: where a resource went, and when. */
    override val auditDetails: Map<String, String> get() = relocations.associate { it.source.id to it.target.id }
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
        planner.build(handle, query.tenantKey, query.relocations).preview
    }
}

@Component
class MoveCatalogResourcesHandler(
    private val jdbi: Jdbi,
    private val planner: CatalogResourceMovePlanner,
    private val declarer: MissingDependencyDeclarer,
    private val themes: app.epistola.suite.themes.ThemeStore,
    private val codeLists: app.epistola.suite.attributes.codelists.CodeListStore,
    private val attributes: app.epistola.suite.attributes.AttributeStore,
    private val objectMapper: tools.jackson.databind.ObjectMapper,
) : CommandHandler<MoveCatalogResources, CatalogResourceMovePreview> {
    override fun handle(command: MoveCatalogResources): CatalogResourceMovePreview = jdbi.inTransaction<CatalogResourceMovePreview, Exception> { handle ->
        handle.createQuery("SELECT pg_advisory_xact_lock(hashtextextended(:tenantKey, 0))")
            .bind("tenantKey", command.tenantKey.value)
            .map { _, _ -> Unit }
            .one()

        val plan = planner.build(handle, command.tenantKey, command.relocations)
        if (plan.preview.planFingerprint != command.expectedPlanFingerprint) {
            throw StaleCatalogResourceMovePlanException()
        }
        if (!plan.preview.executable) throw CatalogResourceMoveBlockedException(plan.preview.blockers)

        // The expected-bytes guard makes every rewrite safe against a concurrent edit.
        for (rewrite in plan.rewrites) {
            val changed = when (rewrite) {
                is JsonRewrite.VariantModel -> handle.createUpdate(
                    """
                    UPDATE template_variants SET template_model = :replacement::jsonb
                    WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")}
                      AND template_resource_id = ${templateAtAddress("tenantKey", "catalogKey", "templateKey")} AND id = :variantKey
                      AND template_model = :expected::jsonb
                    """,
                )
                    .bind("templateKey", rewrite.ownerKey)
                    .bind("variantKey", rewrite.variantKey)
                    .bindRewrite(command.tenantKey, rewrite)
                    .execute()

                is JsonRewrite.StencilContent -> handle.createUpdate(
                    """
                    UPDATE stencils SET content = :replacement::jsonb
                    WHERE tenant_key = :tenantKey
                      AND version_id = ${versionAt("tenantKey", "catalogKey")} AND id = :stencilKey
                      AND content = :expected::jsonb
                    """,
                )
                    .bind("stencilKey", rewrite.ownerKey)
                    .bindRewrite(command.tenantKey, rewrite)
                    .execute()

                is JsonRewrite.ThemeStyles -> rewriteThemeStyles(handle, command.tenantKey, rewrite)

                is JsonRewrite.AttributeContent -> rewriteAttribute(handle, command.tenantKey, rewrite)

                is JsonRewrite.VariantAttributes -> handle.createUpdate(
                    """
                    UPDATE template_variants SET attributes = :replacement::jsonb
                    WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")}
                      AND template_resource_id = ${templateAtAddress("tenantKey", "catalogKey", "templateKey")} AND id = :variantKey
                      AND attributes = :expected::jsonb
                    """,
                )
                    .bind("templateKey", rewrite.ownerKey)
                    .bind("variantKey", rewrite.variantKey)
                    .bindRewrite(command.tenantKey, rewrite)
                    .execute()
            }
            if (changed != 1) throw StaleCatalogResourceMovePlanException()
        }

        for (relocation in planner.applyOrder(plan.preview.relocations)) {
            val resourceId = requireNotNull(relocation.resourceId)
            // The table and key column come from MovableResource, never from caller input.
            val movable = requireNotNull(MovableResource.of(relocation.source.type))
            if (movable == MovableResource.THEME) {
                // A theme is a draft row naming its content (ADR 0033); its content names its key.
                val source = relocation.source.catalogIn(command.tenantKey)
                if (!themes.move(handle, source, relocation.source.key, relocation.target.catalogIn(command.tenantKey), relocation.target.key)) {
                    throw StaleCatalogResourceMovePlanException()
                }
                continue
            }
            if (movable == MovableResource.ATTRIBUTE) {
                // So is an attribute (ADR 0033).
                if (!attributes.move(handle, relocation.source.catalogIn(command.tenantKey), relocation.source.key, relocation.target.catalogIn(command.tenantKey), relocation.target.key)) {
                    throw StaleCatalogResourceMovePlanException()
                }
                continue
            }
            if (movable == MovableResource.CODE_LIST) {
                // So is a code list (ADR 0033); its content names its key.
                if (!codeLists.move(handle, relocation.source.catalogIn(command.tenantKey), relocation.source.key, relocation.target.catalogIn(command.tenantKey), relocation.target.key)) {
                    throw StaleCatalogResourceMovePlanException()
                }
                continue
            }
            val moved = handle.createUpdate(
                """
                UPDATE ${movable.table}
                SET catalog_id = ${catalogAt("tenantKey", "targetCatalogKey")},
                    -- Into the destination's current version; children follow by trigger.
                    version_id = ${versionAt("tenantKey", "targetCatalogKey")},
                    ${movable.keyColumn} = ${movable.keyColumnType?.let { "CAST(:targetKey AS $it)" } ?: ":targetKey"}
                WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "sourceCatalogKey")} AND resource_id = :resourceId
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("resourceId", resourceId)
                .bindCatalog("sourceCatalogKey", relocation.source.catalogIn(command.tenantKey))
                .bindCatalog("targetCatalogKey", relocation.target.catalogIn(command.tenantKey))
                .bind("targetKey", relocation.target.key)
                .execute()
            if (moved != 1) throw StaleCatalogResourceMovePlanException()
            // This one row is the whole relational move. Every type is keyed by its resource_id and
            // every dependant names that, so nothing else refers to the address being changed --
            // owned hierarchies included, and a rename no differently from a move.
        }

        // A reference that now crosses into another catalog makes its catalog depend on that one
        // (contract ADR 0003). The move implies it, so it is declared here rather than left for the
        // next release to refuse.
        declarer.declareMissingInTenant(command.tenantKey)

        plan.preview
    }

    /** The theme's styles replaced, when they are still what the plan saw; 1 when they were, else 0. */
    private fun rewriteThemeStyles(handle: org.jdbi.v3.core.Handle, tenantKey: TenantKey, rewrite: JsonRewrite.ThemeStyles): Int {
        val catalog = app.epistola.suite.common.ids.CatalogId.of(rewrite.catalogKey, app.epistola.suite.common.ids.TenantId(tenantKey))
        val unchanged = handle.createQuery(
            """
            SELECT count(*) FROM current_themes
            WHERE tenant_key = :tenantKey AND catalog_id = ${catalogAt("tenantKey", "catalogKey")} AND id = :themeKey
              AND document_styles = :expectedDocumentStyles::jsonb
              AND block_style_presets IS NOT DISTINCT FROM :expectedPresets::jsonb
            """,
        )
            .bind("tenantKey", tenantKey)
            .bindCatalog("catalogKey", catalog)
            .bind("themeKey", rewrite.ownerKey)
            .bind("expectedDocumentStyles", rewrite.expectedDocumentStyles)
            .bind("expectedPresets", rewrite.expectedPresets)
            .mapTo(Long::class.java)
            .one() > 0
        if (!unchanged) return 0
        val key = app.epistola.suite.common.ids.ThemeKey.of(rewrite.ownerKey)
        val theme = ThemeReads.get(handle, objectMapper, catalog, key) ?: return 0
        val fields = theme.fields().copy(
            documentStyles = objectMapper.readValue(rewrite.replacementDocumentStyles, app.epistola.suite.templates.model.DocumentStyles::class.java),
            blockStylePresets = rewrite.replacementPresets?.let { objectMapper.readValue(it, app.epistola.suite.themes.BlockStylePresets::class.java) },
        )
        // A rewrite follows a move; it is not the user's edit, so whoever edited it last stays.
        return if (themes.save(handle, theme, fields, theme.updatedBy?.value) != null) 1 else 0
    }

    /** The attribute's binding replaced, when its content is still what the plan saw; 1 when it was, else 0. */
    private fun rewriteAttribute(handle: org.jdbi.v3.core.Handle, tenantKey: TenantKey, rewrite: JsonRewrite.AttributeContent): Int {
        val catalog = app.epistola.suite.common.ids.CatalogId.of(rewrite.catalogKey, app.epistola.suite.common.ids.TenantId(tenantKey))
        val id = app.epistola.suite.common.ids.AttributeId(app.epistola.suite.common.ids.AttributeKey.of(rewrite.ownerKey), catalog)
        val (stored, updatedBy) = handle.createQuery(
            "SELECT (payload -> 'resource')::text AS resource, updated_by FROM current_attributes WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND id = :k",
        ).bind("t", tenantKey).bindCatalog("c", catalog).bind("k", rewrite.ownerKey)
            .map { rs, _ -> rs.getString("resource") to rs.getObject("updated_by", java.util.UUID::class.java) }
            .findOne().orElse(null) ?: return 0
        if (objectMapper.readTree(stored) != objectMapper.readTree(rewrite.expected)) return 0
        val replacement = objectMapper.readValue(rewrite.replacement, app.epistola.catalog.protocol.AttributeResource::class.java)
        val fields = app.epistola.suite.attributes.AttributeFields(replacement.name, replacement.allowedValues, replacement.codeListBinding)
        // A rewrite follows a move; it is not the user's edit, so whoever edited it last stays.
        return if (attributes.write(handle, id, fields, updatedBy)) 1 else 0
    }

    private fun org.jdbi.v3.core.statement.Update.bindRewrite(tenantKey: TenantKey, rewrite: JsonRewrite) = bind("tenantKey", tenantKey)
        .bindCatalog("catalogKey", app.epistola.suite.common.ids.CatalogId.of(rewrite.catalogKey, app.epistola.suite.common.ids.TenantId(tenantKey)))
        .bind("replacement", rewrite.replacement)
        .bind("expected", rewrite.expected)
}
