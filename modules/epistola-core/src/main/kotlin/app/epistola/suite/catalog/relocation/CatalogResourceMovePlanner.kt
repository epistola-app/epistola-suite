// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.CatalogEditingProperties
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.bindCatalogs
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.catalogsAt
import app.epistola.suite.catalog.currentTemplateViewJoin
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.graph.ResourceReferenceSites
import app.epistola.suite.catalog.graph.TenantResourceGraphBuilder
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.queries.CatalogEditability
import app.epistola.suite.catalog.releases.ChangeKind
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.ResourceIdentity
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest

@Component
class CatalogResourceMovePlanner(
    private val objectMapper: ObjectMapper,
    private val graphs: TenantResourceGraphBuilder,
    private val editing: CatalogEditingProperties,
    private val repointer: ReferenceRepointer,
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

        val editable = loadEditable(handle, tenantKey, relocations)
        // An address a batch member is vacating is free for another member to take, so occupancy is
        // judged against the batch rather than against the current state alone.
        val vacated = relocations.map { it.source }.toSet()
        val identities = mutableMapOf<ResourceAddress, ResourceIdentity>()

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
            if (source.key != target.key && MovableResource.of(source.type)?.renameable == false) {
                blockers += blocker(
                    "rename-unsupported",
                    "${source.type.wireName} keys are generated identifiers, so they cannot be renamed",
                    source,
                )
            }
            // The executor writes the key straight into the resource row, so a key the type could
            // never have been created with would otherwise fail there, after the plan was approved.
            if (source.key != target.key) {
                MovableResource.of(source.type)?.keyProblem?.invoke(target.key)?.let { problem ->
                    blockers += blocker("invalid-target-key", "'${target.key}' cannot be used: $problem", source)
                }
            }
            if (source.catalogAddress !in editable) {
                blockers += blocker("source-read-only", "${source.catalogAddress} cannot be changed here", source)
            }
            if (target.catalogAddress !in editable) {
                blockers += blocker("target-read-only", "${target.catalogAddress} cannot be changed here", source)
            }
            val resourceId = resolveIdentity(handle, tenantKey, source)
            if (resourceId == null) {
                blockers += blocker("resource-not-found", "${source.id} is not a canonical resource", source)
            } else if (!inChangedVersion(handle, tenantKey, source)) {
                // The registry knows the resource from another draft: the one the move changes lacks it.
                blockers += blocker("not-in-draft", "${source.id} is not in the draft this move changes", source)
            } else {
                identities[source] = resourceId
                // A move changes one draft of its catalog (ADR 0042): the others keep their copy at the
                // old address, under an identity of their own, and are not changed.
                for (draft in draftsLeftBehind(handle, tenantKey, source, resourceId)) {
                    warnings += ResourceMoveWarning(
                        "left-behind-draft",
                        "$draft of ${source.catalogAddress} keeps its own copy of ${source.id} at the old address; it is not changed",
                        source,
                    )
                }
            }

            if (target !in vacated && isAddressTaken(handle, tenantKey, target)) {
                blockers += blocker("target-occupied", "${target.id} is already a resource; give it another key", source)
            }
            // A warning, not a blocker. A *subscriber* is out of this move's reach: an installation
            // that upgrades to a later release of this catalog sees the resource gone rather than
            // moved. Whether that matters depends on who consumes the catalog, which only the
            // operator knows, and blocking on it made a catalog permanently unmovable after a single
            // local release nobody ever pulled.
            if (hasRelease(handle, tenantKey, source.catalogIn(tenantKey))) {
                warnings += ResourceMoveWarning(
                    "released-source",
                    "${source.catalogAddress} has been released; anyone subscribed to it will not follow this change until they re-import",
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

        // References are re-pointed once for the whole batch, so a reference between two moving
        // resources lands on the other's destination rather than the address it is leaving. An aliased
        // reference means the catalog its holder's catalog declares under that alias (ADR 0034, section 9).
        val repoint = repointer.plan(handle, tenantKey, relocations.filter { it.source in identities }, graphs.loadAliases(handle, tenantKey))

        // Catalog ordering is load-bearing for releasing, which pins what a catalog depends on. Checked
        // last and only when the batch would otherwise go ahead: building the graph is the expensive
        // part of planning, and a batch already blocked cannot introduce anything.
        var impact = ReleaseImpact()
        if (blockers.isEmpty()) {
            val graph = graphs.buildOn(handle, tenantKey)
            CatalogDependencyCycles.introducedBy(graph, relocations)?.let { cycle ->
                blockers += blocker(
                    "catalog-dependency-cycle",
                    "This would make ${cycle.joinToString(" and ")} depend on each other, " +
                        "which would leave no order to release them in",
                )
            }
            if (blockers.isEmpty()) impact = releaseImpact(handle, tenantKey, graph, relocations)
        }
        if (blockers.isEmpty()) warnings += readyAgain(repoint)

        val plans = relocations.map { relocation ->
            ResourceRelocationPlan(
                source = relocation.source,
                target = relocation.target,
                resourceId = identities[relocation.source],
                // What names it, and what of its own content names what it leaves behind.
                mutableRewriteCount = repoint.rewrites.count { it.attributedTo == relocation.source } +
                    repoint.moves.filter { it.relocation == relocation }.sumOf { move -> (listOf(move.head) + move.parts).count { it.repointed } },
                // A release keeps its content; references there are not rewritten (only drafts are).
                immutableReferenceCount = 0,
            )
        }
        val fingerprint = fingerprint(plans, blockers, warnings, repoint)
        return CatalogResourceMovePlan(
            preview = CatalogResourceMovePreview(plans, blockers.distinct(), warnings.distinct(), fingerprint, impact.releases, impact.order),
            repoint = repoint,
        )
    }

    private data class ReleaseImpact(val releases: List<ReleaseConsequence> = emptyList(), val order: List<String> = emptyList())

    /**
     * What the move means for releases (ADR 0042), from the reference graph with each moving resource
     * counted at its destination, as the cycle check counts it: a source whose changed draft's base
     * release holds the resource no longer contains it (breaking), a target gains it (additive), and a
     * catalog whose content now uses another it did not depends on it (additive). The catalogs are
     * ordered for release, each after what it depends on.
     */
    private fun releaseImpact(handle: Handle, tenantKey: TenantKey, graph: app.epistola.suite.catalog.graph.TenantResourceGraph, relocations: List<ResourceRelocation>): ReleaseImpact {
        val destinations = relocations.associate { it.source to it.target }
        fun catalogEdges(moved: Boolean): Set<Pair<String, String>> = graph.edges.mapNotNull { edge ->
            val target = edge.target ?: return@mapNotNull null
            val from = (if (moved) destinations[edge.source] else null)?.catalogAddress ?: edge.source.catalogAddress
            val to = (if (moved) destinations[target] else null)?.catalogAddress ?: target.catalogAddress
            (from to to).takeIf { from != to }
        }.toSet()
        val before = catalogEdges(moved = false)
        val after = catalogEdges(moved = true)

        val reasons = linkedMapOf<String, MutableList<Pair<ChangeKind, String>>>()
        fun add(catalog: String, kind: ChangeKind, reason: String) = reasons.getOrPut(catalog) { mutableListOf() }.add(kind to reason)
        for ((source, target) in relocations) {
            if (inBaseRelease(handle, tenantKey, source)) add(source.catalogAddress, ChangeKind.BREAKING, "no longer contains ${source.type.word()} '${source.key}'")
            add(target.catalogAddress, ChangeKind.ADDITIVE, "gains ${target.type.word()} '${target.key}'")
        }
        (after - before).sortedWith(compareBy({ it.first }, { it.second })).forEach { (from, to) -> add(from, ChangeKind.ADDITIVE, "now depends on ${to.substringAfter('/')}") }
        // A catalog that only uses a relocated resource has its references rewritten, so its next
        // release changes too: a fix, which comes after the release holding the resource at its new
        // address. A catalog already listed says so by its other reasons.
        val listed = reasons.keys.toSet()
        graph.edges.mapNotNull { edge -> edge.target?.let { destinations[it] }?.let { edge.source.catalogAddress to it } }
            .filter { (catalog, _) -> catalog !in listed }
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second.id }))
            .forEach { (catalog, target) -> add(catalog, ChangeKind.NONE, "uses ${target.type.word()} '${target.key}' at its new address") }

        val order = releaseOrder(reasons.keys, after)
        val releases = order.map { catalog ->
            val list = reasons.getValue(catalog)
            ReleaseConsequence(catalog, list.maxOf { it.first }, list.map { it.second }.distinct())
        }
        return ReleaseImpact(releases, order)
    }

    /** [catalogs] ordered so each comes after what it depends on by [edges]; ties by address. */
    private fun releaseOrder(catalogs: Set<String>, edges: Set<Pair<String, String>>): List<String> {
        val waitsFor = catalogs.associateWith { catalog -> edges.filter { it.first == catalog && it.second in catalogs }.map { it.second }.toMutableSet() }.toMutableMap()
        val ordered = mutableListOf<String>()
        while (waitsFor.isNotEmpty()) {
            // A cycle is blocked before this runs; whatever remains is taken in address order.
            val next = waitsFor.entries.filter { it.value.isEmpty() }.minByOrNull { it.key }?.key ?: waitsFor.keys.min()
            ordered += next
            waitsFor.remove(next)
            waitsFor.values.forEach { it.remove(next) }
        }
        return ordered
    }

    /** Whether the version the move changes in [source]'s catalog (the targeted draft, else the next) holds [source]. */
    private fun inChangedVersion(handle: Handle, tenantKey: TenantKey, source: ResourceAddress): Boolean = handle.createQuery(
        """
        SELECT EXISTS (
            SELECT 1 FROM release_wire_resources r
            WHERE r.tenant_key = :tenantKey AND r.version_id = ${versionAt("tenantKey", "catalogKey")}
              AND r.resource_type = :resourceType AND r.resource_key = :resourceKey
        )
        """,
    )
        .bind("tenantKey", tenantKey)
        .bindCatalog("catalogKey", source.catalogIn(tenantKey))
        .bind("resourceType", source.type.wireName)
        .bind("resourceKey", source.key)
        .mapTo(Boolean::class.java)
        .one()

    /** Whether the release the changed draft of [source]'s catalog is based on holds [source]. */
    private fun inBaseRelease(handle: Handle, tenantKey: TenantKey, source: ResourceAddress): Boolean = handle.createQuery(
        """
        SELECT EXISTS (
            SELECT 1 FROM catalog_versions d
            JOIN catalog_versions b ON b.tenant_key = d.tenant_key AND b.catalog_id = d.catalog_id AND b.state = 'released' AND b.version = d.base_version
            JOIN release_wire_resources r ON r.tenant_key = b.tenant_key AND r.version_id = b.version_id
            WHERE d.tenant_key = :tenantKey AND d.version_id = ${versionAt("tenantKey", "catalogKey")}
              AND r.resource_type = :resourceType AND r.resource_key = :resourceKey
        )
        """,
    )
        .bind("tenantKey", tenantKey)
        .bindCatalog("catalogKey", source.catalogIn(tenantKey))
        .bind("resourceType", source.type.wireName)
        .bind("resourceKey", source.key)
        .mapTo(Boolean::class.java)
        .one()

    private fun CatalogResourceType.word(): String = name.lowercase().replace('_', ' ')

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

    /** The catalogs of [relocations] that can be changed here (ADR 0032); one the tenant lacks is not. */
    private fun loadEditable(
        handle: Handle,
        tenantKey: TenantKey,
        relocations: List<ResourceRelocation>,
    ): Set<String> {
        val keys = relocations.flatMap { listOf(it.source.catalogIn(tenantKey), it.target.catalogIn(tenantKey)) }.distinct()
        if (keys.isEmpty()) return emptySet()
        return handle.createQuery(
            "SELECT namespace || '/' || catalog_key AS catalog_key, COALESCE(source_url LIKE 'classpath:%', FALSE) AS supplied FROM catalogs WHERE tenant_key = :tenantKey AND catalog_id IN ${catalogsAt("tenantKey", "keys")}",
        )
            .bind("tenantKey", tenantKey)
            .bindCatalogs("keys", keys)
            .map { rs, _ -> rs.getString("catalog_key") to rs.getBoolean("supplied") }
            .list()
            .filter { (_, supplied) -> CatalogEditability.of(supplied, editing.enabled).editable }
            .mapTo(mutableSetOf()) { it.first }
    }

    /**
     * The other drafts of [source]'s catalog that hold the resource [resourceId]: everything but the
     * version the move changes (the targeted draft, else the next version), by name.
     */
    private fun draftsLeftBehind(handle: Handle, tenantKey: TenantKey, source: ResourceAddress, resourceId: ResourceIdentity): List<String> = handle.createQuery(
        """
        SELECT COALESCE(v.name, 'The next release') AS draft
        FROM release_wire_resources r
        JOIN catalog_versions v ON v.tenant_key = r.tenant_key AND v.version_id = r.version_id AND v.state = 'draft'
        WHERE r.tenant_key = :tenantKey AND r.resource_id = :resourceId
          AND r.version_id <> ${versionAt("tenantKey", "catalogKey")}
        ORDER BY v.created_at, v.version_id
        """,
    )
        .bind("tenantKey", tenantKey)
        .bind("resourceId", resourceId.value)
        .bindCatalog("catalogKey", source.catalogIn(tenantKey))
        .mapTo(String::class.java)
        .list()

    private fun resolveIdentity(handle: Handle, tenantKey: TenantKey, source: ResourceAddress): ResourceIdentity? = handle.createQuery(
        """
        SELECT resource_id FROM catalog_resources
        WHERE tenant_key = :tenantKey AND resource_type = :resourceType
          AND catalog_id = ${catalogAt("tenantKey", "catalogKey")} AND resource_key = :resourceKey
        """,
    )
        .bind("tenantKey", tenantKey)
        .bind("resourceType", source.type.wireName)
        .bindCatalog("catalogKey", source.catalogIn(tenantKey))
        .bind("resourceKey", source.key)
        // Mapped by hand: JDBI's Kotlin plugin claims any Kotlin class for constructor binding
        // before the column-mapper registry is consulted, so mapTo on a value class does not work.
        .map { rs, _ -> ResourceIdentity.of(rs.getString("resource_id")) }
        .findOne()
        .orElse(null)

    private fun isAddressTaken(handle: Handle, tenantKey: TenantKey, target: ResourceAddress): Boolean = handle.createQuery(
        """
        SELECT EXISTS(
            SELECT 1 FROM catalog_resources
            WHERE tenant_key = :tenantKey AND resource_type = :resourceType
              AND catalog_id = ${catalogAt("tenantKey", "catalogKey")} AND resource_key = :resourceKey
        )
        """,
    )
        .bind("tenantKey", tenantKey)
        .bind("resourceType", target.type.wireName)
        .bindCatalog("catalogKey", target.catalogIn(tenantKey))
        .bind("resourceKey", target.key)
        .mapTo(Boolean::class.java)
        .one()

    private fun hasRelease(handle: Handle, tenantKey: TenantKey, catalog: app.epistola.suite.common.ids.CatalogId): Boolean = handle.createQuery(
        "SELECT EXISTS(SELECT 1 FROM catalog_versions WHERE state = 'released' AND tenant_key = :tenantKey AND catalog_id = ${catalogAt("tenantKey", "catalogKey")})",
    )
        .bind("tenantKey", tenantKey)
        .bindCatalog("catalogKey", catalog)
        .mapTo(Boolean::class.java)
        .one()

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
        repoint: RepointPlan,
    ): String {
        val input = buildString {
            plans.sortedBy { it.source.id }.forEach {
                appendLine("${it.source.id}->${it.target.id}:${it.resourceId}:${it.immutableReferenceCount}")
            }
            blockers.sortedBy { it.code + it.message }.forEach { appendLine("${it.code}:${it.source?.id}:${it.message}") }
            // Warnings are consent, not just information: a warning appearing between preview and
            // execute must invalidate the plan the same way a blocker would.
            warnings.sortedBy { it.code + it.message }.forEach { appendLine("warn:${it.code}:${it.source?.id}:${it.message}") }
            (repoint.rewrites + repoint.moves.flatMap { listOf(it.head) + it.parts }).sortedBy { it.identity }
                .forEach { appendLine("${it.identity}:${it.row.contentHash}:${it.contentHash}") }
        }
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * A ready mark is for the content an author approved. Rewriting a resource's references, or
     * moving it, changes that content, so each one is to be marked ready again before its catalog
     * releases: said in the preview, rather than first met as a refused release in another catalog.
     * A long list names the first few.
     */
    private fun readyAgain(repoint: RepointPlan): List<ResourceMoveWarning> = (repoint.rewrites.map { it.row } + repoint.moves.map { it.head.row.copy(catalogAddress = it.relocation.target.catalogAddress, key = it.relocation.target.key) })
        .groupBy({ it.catalogAddress }, { row -> row.parentKey?.let { "template '$it'" } ?: "${row.type} '${row.key}'" })
        .toSortedMap()
        .map { (catalog, changed) ->
            val names = changed.distinct().sorted()
            val shown = if (names.size > 3) names.take(3) + "${names.size - 3} more" else names
            ResourceMoveWarning(
                "ready-again",
                "In $catalog, ${shown.joinToString()} ${if (names.size == 1) "changes and needs" else "change and need"} marking ready again before it is released",
            )
        }

    private fun blocker(code: String, message: String, source: ResourceAddress? = null) = ResourceMoveBlocker(code, message, source)
}

internal data class CatalogResourceMovePlan(
    val preview: CatalogResourceMovePreview,
    val repoint: RepointPlan,
)
