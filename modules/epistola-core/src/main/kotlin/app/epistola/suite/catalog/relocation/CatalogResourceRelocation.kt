// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.graph.ResourceReferenceSites
import app.epistola.suite.catalog.graph.TenantResourceGraphBuilder
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
 * Moving and renaming are the same operation — both change the canonical address while identity
 * stays put — so separating them would mean two commands with the same aliasing, rewriting and
 * validation. Carrying the key here also gives a collision somewhere to go: a resource whose key is
 * already taken in the destination can land under a different one, where a catalog-only move could
 * only be blocked.
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
    val resourceId: UUID?,
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
        planner.build(handle, query.tenantKey, query.relocations).preview
    }
}

@Component
class MoveCatalogResourcesHandler(
    private val jdbi: Jdbi,
    private val planner: CatalogResourceMovePlanner,
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

        // A rewrite may target a published version -- only ever the pin of a moving resource's own
        // relative references, never a re-pointing (see CatalogResourceMovePlanner.rewriteContent).
        // The expected-bytes guard is what makes touching one safe.
        for (rewrite in plan.rewrites) {
            val changed = when (rewrite) {
                is JsonRewrite.TemplateVersion -> handle.createUpdate(
                    """
                    UPDATE template_versions SET template_model = :replacement::jsonb
                    WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                      AND template_key = :templateKey AND variant_key = :variantKey AND id = :version
                      AND template_model = :expected::jsonb
                    """,
                )
                    .bind("templateKey", rewrite.ownerKey)
                    .bind("variantKey", rewrite.variantKey)
                    .bind("version", rewrite.version)
                    .bindRewrite(command.tenantKey, rewrite)
                    .execute()

                is JsonRewrite.StencilVersion -> handle.createUpdate(
                    """
                    UPDATE stencil_versions SET content = :replacement::jsonb
                    WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                      AND stencil_key = :stencilKey AND id = :version
                      AND content = :expected::jsonb
                    """,
                )
                    .bind("stencilKey", rewrite.ownerKey)
                    .bind("version", rewrite.version)
                    .bindRewrite(command.tenantKey, rewrite)
                    .execute()

                is JsonRewrite.VariantAttributes -> handle.createUpdate(
                    """
                    UPDATE template_variants SET attributes = :replacement::jsonb
                    WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                      AND template_key = :templateKey AND id = :variantKey
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

        // Aliases are inserted for every member before any resource moves, so a batch where one
        // member takes an address another is vacating cannot depend on the order they are applied.
        for (plan in plan.preview.relocations) {
            val resourceId = requireNotNull(plan.resourceId)
            handle.createUpdate(
                """
                INSERT INTO catalog_resource_aliases (
                    tenant_key, resource_type, catalog_key, resource_key, target_resource_id
                ) VALUES (:tenantKey, :resourceType, :catalogKey, :resourceKey, :resourceId)
                ON CONFLICT (tenant_key, resource_type, catalog_key, resource_key) DO UPDATE
                SET target_resource_id = EXCLUDED.target_resource_id
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("resourceType", plan.source.type.wireName)
                .bind("catalogKey", plan.source.catalogKey)
                .bind("resourceKey", plan.source.key)
                .bind("resourceId", resourceId)
                .execute()

            // Reclaiming an address this resource previously held makes the alias it left there
            // redundant.
            handle.createUpdate(
                """
                DELETE FROM catalog_resource_aliases
                WHERE tenant_key = :tenantKey AND resource_type = :resourceType
                  AND catalog_key = :catalogKey AND resource_key = :resourceKey
                  AND target_resource_id = :resourceId
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("resourceType", plan.target.type.wireName)
                .bind("catalogKey", plan.target.catalogKey)
                .bind("resourceKey", plan.target.key)
                .bind("resourceId", resourceId)
                .execute()
        }

        for (relocation in planner.applyOrder(plan.preview.relocations)) {
            val resourceId = requireNotNull(relocation.resourceId)
            // The table and key column come from MovableResource, never from caller input.
            val movable = requireNotNull(MovableResource.of(relocation.source.type))
            val moved = handle.createUpdate(
                """
                UPDATE ${movable.table}
                SET catalog_key = :targetCatalogKey,
                    ${movable.keyColumn} = ${movable.keyColumnType?.let { "CAST(:targetKey AS $it)" } ?: ":targetKey"}
                WHERE tenant_key = :tenantKey AND resource_id = :resourceId
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("resourceId", resourceId)
                .bind("targetCatalogKey", relocation.target.catalogKey)
                .bind("targetKey", relocation.target.key)
                .execute()
            if (moved != 1) throw StaleCatalogResourceMovePlanException()
            // Owned hierarchies follow their parent's address by database rule -- the ON UPDATE
            // CASCADE foreign keys fire on any referenced column, so a rename carries them too.
        }

        plan.preview
    }

    private fun org.jdbi.v3.core.statement.Update.bindRewrite(tenantKey: TenantKey, rewrite: JsonRewrite) = bind("tenantKey", tenantKey)
        .bind("catalogKey", rewrite.catalogKey)
        .bind("replacement", rewrite.replacement)
        .bind("expected", rewrite.expected)
}

@Component
class CatalogResourceMovePlanner(
    private val objectMapper: ObjectMapper,
    private val graphs: TenantResourceGraphBuilder,
) {
    internal fun build(
        handle: Handle,
        tenantKey: TenantKey,
        relocations: List<ResourceRelocation>,
    ): CatalogResourceMovePlan {
        val blockers = mutableListOf<ResourceMoveBlocker>()
        val warnings = mutableListOf<ResourceMoveWarning>()
        if (relocations.isEmpty()) blockers += blocker("empty-batch", "Select at least one resource to move")
        relocations.groupBy { it.source }.filterValues { it.size > 1 }.keys.forEach {
            blockers += blocker("duplicate-source", "${it.id} is listed more than once", it)
        }
        relocations.groupBy { it.target }.filterValues { it.size > 1 }.forEach { (target, group) ->
            blockers += blocker("colliding-targets", "More than one resource would land on ${target.id}", group.first().source)
        }

        val catalogTypes = loadCatalogTypes(handle, tenantKey, relocations)
        // An address a batch member is vacating is free for another member to take, so occupancy is
        // judged against the batch rather than against the current state alone.
        val vacated = relocations.map { it.source }.toSet()
        val identities = mutableMapOf<ResourceAddress, UUID>()

        for (relocation in relocations) {
            val (source, target) = relocation
            if (source == target) {
                blockers += blocker("unchanged-address", "${source.id} would not move", source)
            }
            if (MovableResource.of(source.type) == null) {
                blockers += blocker(
                    "unsupported-resource-type",
                    "${source.type.wireName} is not relocatable yet; its table is still keyed by address",
                    source,
                )
            }
            if (catalogTypes[source.catalogKey] != "AUTHORED") {
                blockers += blocker("source-read-only", "${source.catalogKey} must be authored and editable", source)
            }
            if (catalogTypes[target.catalogKey] != "AUTHORED") {
                blockers += blocker("target-read-only", "${target.catalogKey} must be authored and editable", source)
            }

            val resourceId = resolveIdentity(handle, tenantKey, source)
            if (resourceId == null) {
                blockers += blocker("resource-not-found", "${source.id} is not a canonical resource", source)
            } else {
                identities[source] = resourceId
            }

            if (target !in vacated && isAddressTaken(handle, tenantKey, target, resourceId)) {
                blockers += blocker("target-occupied", "${target.id} is already a resource or retained alias", source)
            }
            // A warning, not a blocker. Within this installation the move is well-defined -- the
            // alias keeps every local reference resolving. What it cannot reach is a *subscriber*:
            // aliases are tenant-local, so an installation that upgrades to a later release of this
            // catalog sees the resource gone rather than moved. Whether that matters depends on who
            // consumes the catalog, which only the operator knows, and blocking on it made a
            // catalog permanently unmovable after a single local release nobody ever pulled.
            if (hasRelease(handle, tenantKey, source.catalogKey)) {
                warnings += ResourceMoveWarning(
                    "released-source",
                    "${source.catalogKey} has been released; anyone subscribed to it will not follow this move until they re-import",
                    source,
                )
            }
        }

        // A member may take an address another member is vacating, but the updates are applied one
        // at a time and the address uniqueness is checked per statement -- so the handovers have to
        // be orderable. A chain can be; a cycle cannot, and no ordering exists that avoids a
        // transient collision.
        addressHandoverCycle(relocations)?.let { cycle ->
            blockers += blocker(
                "address-swap-cycle",
                "${cycle.joinToString(" and ")} would exchange addresses, which cannot be applied in any order",
            )
        }

        // Content references are rewritten once for the whole batch, so a reference between two
        // moving resources lands on the other's destination rather than the address it is leaving.
        val contentMoves = relocations
            .filter { MovableResource.of(it.source.type)?.contentReferenceKinds?.isNotEmpty() == true }
            .associate { it.source to it.target }

        val rewrites = mutableListOf<JsonRewrite>()
        val immutableBySource = mutableMapOf<ResourceAddress, Int>()
        rewriteContent(handle, tenantKey, contentMoves, relocations, rewrites, immutableBySource)
        for (relocation in relocations) {
            if (MovableResource.of(relocation.source.type) == MovableResource.ATTRIBUTE && relocation.source in identities) {
                rewrites += attributeKeyRewrites(handle, tenantKey, relocation.source, relocation.target)
            }
        }

        // Catalog ordering is load-bearing for snapshot restore, which throws on a cycle. Checked
        // last and only when the batch would otherwise go ahead: building the graph is the expensive
        // part of planning, and a batch already blocked cannot introduce anything.
        if (blockers.isEmpty()) {
            val graph = graphs.buildOn(handle, tenantKey, includeHistory = false)
            CatalogDependencyCycles.introducedBy(graph, relocations)?.let { cycle ->
                blockers += blocker(
                    "catalog-dependency-cycle",
                    "This would make ${cycle.joinToString(" and ")} depend on each other, " +
                        "which would leave the tenant's snapshots unrestorable",
                )
            }
        }

        val plans = relocations.map { relocation ->
            ResourceRelocationPlan(
                source = relocation.source,
                target = relocation.target,
                resourceId = identities[relocation.source],
                mutableRewriteCount = rewrites.count { it.attributedTo == relocation.source },
                immutableReferenceCount = immutableBySource[relocation.source] ?: 0,
            )
        }
        val fingerprint = fingerprint(plans, blockers, warnings, rewrites)
        return CatalogResourceMovePlan(
            preview = CatalogResourceMovePreview(plans, blockers.distinct(), warnings.distinct(), fingerprint),
            rewrites = rewrites,
        )
    }

    /**
     * Members that must be applied before others, because they are vacating an address the other
     * takes. Returns the members forming a cycle when no such order exists.
     */
    private fun addressHandoverCycle(relocations: List<ResourceRelocation>): List<String>? {
        val vacatedBy = relocations.associate { it.source to it }
        val waitsFor = relocations.associateWith { relocation ->
            vacatedBy[relocation.target]?.takeIf { it != relocation }?.let { setOf(it) } ?: emptySet()
        }.toMutableMap()

        while (true) {
            val next = waitsFor.entries.filter { it.value.isEmpty() }.minByOrNull { it.key.source.id }?.key ?: break
            waitsFor.remove(next)
            waitsFor.replaceAll { _, blockedBy -> blockedBy - next }
        }
        return waitsFor.keys.map { it.source.id }.sorted().takeIf { it.isNotEmpty() }
    }

    /**
     * Orders a batch so a member vacating an address is applied before the member that takes it.
     * Only meaningful once [addressHandoverCycle] has confirmed an order exists.
     */
    internal fun applyOrder(relocations: List<ResourceRelocationPlan>): List<ResourceRelocationPlan> {
        val vacatedBy = relocations.associateBy { it.source }
        val ordered = mutableListOf<ResourceRelocationPlan>()
        val remaining = relocations.toMutableList()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { candidate ->
                vacatedBy[candidate.target]?.takeIf { it != candidate }?.let { it !in remaining } ?: true
            } ?: remaining.first()
            ordered += next
            remaining -= next
        }
        return ordered
    }

    private fun loadCatalogTypes(
        handle: Handle,
        tenantKey: TenantKey,
        relocations: List<ResourceRelocation>,
    ): Map<String, String> {
        val keys = relocations.flatMap { listOf(it.source.catalogKey, it.target.catalogKey) }.distinct()
        if (keys.isEmpty()) return emptyMap()
        return handle.createQuery("SELECT id::text, type::text FROM catalogs WHERE tenant_key = :tenantKey AND id IN (<keys>)")
            .bind("tenantKey", tenantKey)
            .bindList("keys", keys)
            .map { rs, _ -> rs.getString("id") to rs.getString("type") }
            .list()
            .toMap()
    }

    private fun resolveIdentity(handle: Handle, tenantKey: TenantKey, source: ResourceAddress): UUID? = handle.createQuery(
        """
        SELECT resource_id FROM catalog_resources
        WHERE tenant_key = :tenantKey AND resource_type = :resourceType
          AND catalog_key = :catalogKey AND resource_key = :resourceKey
        """,
    )
        .bind("tenantKey", tenantKey)
        .bind("resourceType", source.type.wireName)
        .bind("catalogKey", source.catalogKey)
        .bind("resourceKey", source.key)
        .mapTo(UUID::class.java)
        .findOne()
        .orElse(null)

    private fun isAddressTaken(
        handle: Handle,
        tenantKey: TenantKey,
        target: ResourceAddress,
        movingResourceId: UUID?,
    ): Boolean = handle.createQuery(
        """
        SELECT EXISTS(
            SELECT 1 FROM catalog_resources
            WHERE tenant_key = :tenantKey AND resource_type = :resourceType
              AND catalog_key = :catalogKey AND resource_key = :resourceKey
            UNION ALL
            -- An alias this very resource left behind does not occupy the address: returning to a
            -- previously held address is a supported undo.
            SELECT 1 FROM catalog_resource_aliases
            WHERE tenant_key = :tenantKey AND resource_type = :resourceType
              AND catalog_key = :catalogKey AND resource_key = :resourceKey
              AND target_resource_id IS DISTINCT FROM :resourceId
        )
        """,
    )
        .bind("tenantKey", tenantKey)
        .bind("resourceType", target.type.wireName)
        .bind("catalogKey", target.catalogKey)
        .bind("resourceKey", target.key)
        .bind("resourceId", movingResourceId)
        .mapTo(Boolean::class.java)
        .one()

    private fun hasRelease(handle: Handle, tenantKey: TenantKey, catalogKey: String): Boolean = handle.createQuery(
        "SELECT EXISTS(SELECT 1 FROM catalog_releases WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey)",
    )
        .bind("tenantKey", tenantKey)
        .bind("catalogKey", catalogKey)
        .mapTo(Boolean::class.java)
        .one()

    /**
     * One pass over versioned content for two rewrites.
     *
     * References *to* a moving resource are re-pointed at its destination where the holder is a
     * draft; a published holder keeps its bytes, resolves through the alias, and is counted as such.
     * Relative references *inside* a moving resource are pinned to the catalog they resolve against
     * today -- published versions included, because the owner leaving is exactly what would change
     * their meaning, and versions never age out. A catalog with a release cannot be moved out of, so
     * the pin never touches released content. Content written since references became qualified on
     * write has nothing left to pin.
     */
    private fun rewriteContent(
        handle: Handle,
        tenantKey: TenantKey,
        contentMoves: Map<ResourceAddress, ResourceAddress>,
        relocations: List<ResourceRelocation>,
        rewrites: MutableList<JsonRewrite>,
        immutableBySource: MutableMap<ResourceAddress, Int>,
    ) {
        val movingTemplates = relocations.filter { it.source.type == CatalogResourceType.TEMPLATE }.associateBy { it.source }
        val movingStencils = relocations.filter { it.source.type == CatalogResourceType.STENCIL }.associateBy { it.source }
        if (contentMoves.isEmpty() && movingTemplates.isEmpty() && movingStencils.isEmpty()) return

        // Only a batch that moves a referenced type has to look at every holder in the tenant; one
        // that moves templates alone needs nothing but the templates' own versions.
        val everyHolder = contentMoves.isNotEmpty()
        for (row in loadTemplateVersions(handle, tenantKey, if (everyHolder) null else movingTemplates.keys)) {
            val owner = movingTemplates[ResourceAddress(CatalogResourceType.TEMPLATE, row.catalogKey, row.ownerKey)]
            rewriteRow(row, owner, contentMoves, immutableBySource)?.let { result ->
                rewrites += JsonRewrite.TemplateVersion(
                    row.catalogKey,
                    row.ownerKey,
                    row.variantKey!!,
                    row.version,
                    row.rawJson,
                    result.json.toString(),
                    result.attributedTo,
                )
            }
        }
        for (row in loadStencilVersions(handle, tenantKey, if (everyHolder) null else movingStencils.keys)) {
            val owner = movingStencils[ResourceAddress(CatalogResourceType.STENCIL, row.catalogKey, row.ownerKey)]
            rewriteRow(row, owner, contentMoves, immutableBySource)?.let { result ->
                rewrites += JsonRewrite.StencilVersion(
                    row.catalogKey,
                    row.ownerKey,
                    row.version,
                    row.rawJson,
                    result.json.toString(),
                    result.attributedTo,
                )
            }
        }
    }

    /** The rewritten payload for one version, or null when it needs no change. */
    private fun rewriteRow(
        row: JsonOwnerRow,
        owner: ResourceRelocation?,
        contentMoves: Map<ResourceAddress, ResourceAddress>,
        immutableBySource: MutableMap<ResourceAddress, Int>,
    ): RewriteResult? {
        var result = applyContentMoves(row.json, row.catalogKey, contentMoves)
        if (row.status != "draft") {
            // Published: references to moved resources keep their bytes and resolve through the alias.
            result.attributedTo?.let { immutableBySource.merge(it, 1, Int::plus) }
            result = RewriteResult(row.json.deepCopy(), changed = false)
        }
        if (owner != null) result = result.pinningRelative(row.catalogKey, owner.source)
        return result.takeIf { it.changed }
    }

    /** Points every reference to a moving resource at that resource's destination. */
    private fun applyContentMoves(
        root: JsonNode,
        ownerCatalog: String,
        contentMoves: Map<ResourceAddress, ResourceAddress>,
    ): RewriteResult {
        val copy = root.deepCopy()
        var changed = false
        var attributedTo: ResourceAddress? = null
        for (site in ResourceReferenceSites.scan(copy)) {
            val referenced = ResourceAddress(site.kind.type, site.catalogKey ?: ownerCatalog, site.key)
            val target = contentMoves[referenced] ?: continue
            site.setCatalogKey(target.catalogKey)
            site.setKey(target.key)
            changed = true
            attributedTo = attributedTo ?: referenced
        }
        return RewriteResult(copy, changed, attributedTo)
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
        target: ResourceAddress,
    ): List<JsonRewrite> {
        val oldKey = source.catalogKey + "." + source.key
        val newKey = target.catalogKey + "." + target.key
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
                    source,
                )
            }
            .list()
    }

    private fun loadTemplateVersions(handle: Handle, tenantKey: TenantKey, owners: Set<ResourceAddress>?): List<JsonOwnerRow> {
        if (owners != null && owners.isEmpty()) return emptyList()
        return handle.createQuery(
            """
            SELECT catalog_key::text, template_key::text owner_key, variant_key::text, id, status, template_model::text json
            FROM template_versions
            WHERE tenant_key = :tenantKey ${ownerFilter(owners, "template_key")}
            ORDER BY catalog_key, template_key, variant_key, id
            """,
        )
            .bind("tenantKey", tenantKey)
            .bindOwners(owners)
            .map { rs, _ ->
                val raw = rs.getString("json")
                JsonOwnerRow(rs.getString("catalog_key"), rs.getString("owner_key"), rs.getString("variant_key"), rs.getInt("id"), rs.getString("status"), raw, objectMapper.readTree(raw))
            }.list()
    }

    private fun loadStencilVersions(handle: Handle, tenantKey: TenantKey, owners: Set<ResourceAddress>?): List<JsonOwnerRow> {
        if (owners != null && owners.isEmpty()) return emptyList()
        return handle.createQuery(
            """
            SELECT catalog_key::text, stencil_key::text owner_key, id, status, content::text json
            FROM stencil_versions
            WHERE tenant_key = :tenantKey ${ownerFilter(owners, "stencil_key")}
            ORDER BY catalog_key, stencil_key, id
            """,
        )
            .bind("tenantKey", tenantKey)
            .bindOwners(owners)
            .map { rs, _ ->
                val raw = rs.getString("json")
                JsonOwnerRow(rs.getString("catalog_key"), rs.getString("owner_key"), null, rs.getInt("id"), rs.getString("status"), raw, objectMapper.readTree(raw))
            }.list()
    }

    /**
     * Restricts a version query to the versions owned by [owners]; null means every version in the
     * tenant. Only the column name is interpolated, and it is a literal from the caller; the
     * addresses are bound.
     */
    private fun ownerFilter(owners: Set<ResourceAddress>?, keyColumn: String): String = if (owners == null) {
        ""
    } else {
        owners.indices.joinToString(" OR ", prefix = "AND (", postfix = ")") { "(catalog_key = :ownerCatalog$it AND $keyColumn = :ownerKey$it)" }
    }

    private fun org.jdbi.v3.core.statement.Query.bindOwners(owners: Set<ResourceAddress>?) = apply {
        owners?.forEachIndexed { index, owner ->
            bind("ownerCatalog$index", owner.catalogKey)
            bind("ownerKey$index", owner.key)
        }
    }

    /**
     * Pins this payload's relative references to [catalogKey] -- the catalog they resolve against
     * before their owner moves -- attributing the change to [owner].
     */
    private fun RewriteResult.pinningRelative(catalogKey: String, owner: ResourceAddress): RewriteResult {
        var pinned = changed
        for (site in ResourceReferenceSites.scan(json)) {
            if (!site.kind.relativeWhenUnqualified || site.catalogKey != null) continue
            site.setCatalogKey(catalogKey)
            pinned = true
        }
        return RewriteResult(json, pinned, attributedTo ?: owner)
    }

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
        plans: List<ResourceRelocationPlan>,
        blockers: List<ResourceMoveBlocker>,
        warnings: List<ResourceMoveWarning>,
        rewrites: List<JsonRewrite>,
    ): String {
        val input = buildString {
            plans.sortedBy { it.source.id }.forEach {
                appendLine("${it.source.id}->${it.target.id}:${it.resourceId}:${it.immutableReferenceCount}")
            }
            blockers.sortedBy { it.code + it.message }.forEach { appendLine("${it.code}:${it.source?.id}:${it.message}") }
            // Warnings are consent, not just information: a warning appearing between preview and
            // execute must invalidate the plan the same way a blocker would.
            warnings.sortedBy { it.code + it.message }.forEach { appendLine("warn:${it.code}:${it.source?.id}:${it.message}") }
            rewrites.sortedBy(JsonRewrite::identity).forEach { appendLine("${it.identity}:${it.expected}:${it.replacement}") }
        }
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun blocker(code: String, message: String, source: ResourceAddress? = null) = ResourceMoveBlocker(code, message, source)

    private data class JsonOwnerRow(
        val catalogKey: String,
        val ownerKey: String,
        val variantKey: String?,
        val version: Int,
        val status: String,
        val rawJson: String,
        val json: JsonNode,
    )

    private data class RewriteResult(val json: JsonNode, val changed: Boolean, val attributedTo: ResourceAddress? = null)
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

    /** Which relocation in the batch caused this rewrite; null when it could not be attributed. */
    val attributedTo: ResourceAddress?

    data class TemplateVersion(
        override val catalogKey: String,
        override val ownerKey: String,
        val variantKey: String,
        override val version: Int,
        override val expected: String,
        override val replacement: String,
        override val attributedTo: ResourceAddress?,
    ) : JsonRewrite {
        override val identity get() = "template:$catalogKey:$ownerKey:$variantKey:$version"
    }

    data class StencilVersion(
        override val catalogKey: String,
        override val ownerKey: String,
        override val version: Int,
        override val expected: String,
        override val replacement: String,
        override val attributedTo: ResourceAddress?,
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
        override val attributedTo: ResourceAddress?,
    ) : JsonRewrite {
        override val version = 0
        override val identity get() = "variant-attributes:$catalogKey:$ownerKey:$variantKey"
    }
}
