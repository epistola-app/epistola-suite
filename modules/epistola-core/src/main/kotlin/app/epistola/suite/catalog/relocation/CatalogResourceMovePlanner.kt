// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.graph.ResourceReferenceSites
import app.epistola.suite.catalog.graph.TenantResourceGraphBuilder
import app.epistola.suite.common.ids.ResourceIdentity
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.templates.templateJoin
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
        rewrites += attributeKeyRewrites(
            handle,
            tenantKey,
            relocations.filter { MovableResource.of(it.source.type) == MovableResource.ATTRIBUTE && it.source in identities },
        )

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

    private fun resolveIdentity(handle: Handle, tenantKey: TenantKey, source: ResourceAddress): ResourceIdentity? = handle.createQuery(
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
        // Mapped by hand: JDBI's Kotlin plugin claims any Kotlin class for constructor binding
        // before the column-mapper registry is consulted, so mapTo on a value class does not work.
        .map { rs, _ -> ResourceIdentity.of(rs.getString("resource_id")) }
        .findOne()
        .orElse(null)

    private fun isAddressTaken(
        handle: Handle,
        tenantKey: TenantKey,
        target: ResourceAddress,
        movingResourceId: ResourceIdentity?,
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
     * their meaning, and versions never age out. A released source catalog only warns, so the pin can
     * touch content a release already covered; the `released-source` warning is fingerprinted into
     * the plan, so executing one means having seen it. Content written since references became
     * qualified on write has nothing left to pin.
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
        val movingThemes = relocations.filter { it.source.type == CatalogResourceType.THEME }.associateBy { it.source }
        rewriteThemeStyles(handle, tenantKey, contentMoves, movingThemes, rewrites)
        pinThemeSnapshots(handle, tenantKey, movingThemes.values.filter { it.source.catalogKey != it.target.catalogKey }, rewrites)
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

    /**
     * A theme's styles name the fonts it uses. They are live, unversioned configuration, so they are
     * rewritten like a draft: a reference to a moving font is re-pointed at its destination, and a
     * moving theme's own relative references are pinned to the catalog they resolve against today,
     * as a moving stencil's are. Left alone, a moved font leaves every theme naming its old address
     * -- exported as-is, and unpublishable when the theme shares the font's catalog -- and a moved
     * theme's relative font resolves in a catalog the font was never in.
     */
    private fun rewriteThemeStyles(
        handle: Handle,
        tenantKey: TenantKey,
        contentMoves: Map<ResourceAddress, ResourceAddress>,
        movingThemes: Map<ResourceAddress, ResourceRelocation>,
        rewrites: MutableList<JsonRewrite>,
    ) {
        if (contentMoves.keys.none { it.type == CatalogResourceType.FONT } && movingThemes.isEmpty()) return
        handle.createQuery(
            """
            SELECT catalog_key::text, id::text theme_key, document_styles::text, block_style_presets::text presets
            FROM themes WHERE tenant_key = :tenantKey
            ORDER BY catalog_key, id
            """,
        )
            .bind("tenantKey", tenantKey)
            .map { rs, _ ->
                val catalogKey = rs.getString("catalog_key")
                val themeKey = rs.getString("theme_key")
                val owner = movingThemes[ResourceAddress(CatalogResourceType.THEME, catalogKey, themeKey)]
                fun rewrite(raw: String?): Pair<String?, ResourceAddress?> {
                    if (raw == null) return null to null
                    var result = applyContentMoves(objectMapper.readTree(raw), catalogKey, contentMoves)
                    if (owner != null) result = result.pinningRelative(catalogKey, owner.source)
                    return (if (result.changed) result.json.toString() else null) to result.attributedTo
                }
                val documentStyles = rs.getString("document_styles")
                val presets = rs.getString("presets")
                val (newDocumentStyles, documentCause) = rewrite(documentStyles)
                val (newPresets, presetsCause) = rewrite(presets)
                if (newDocumentStyles == null && newPresets == null) return@map null
                JsonRewrite.ThemeStyles(
                    catalogKey = catalogKey,
                    ownerKey = themeKey,
                    expectedDocumentStyles = documentStyles,
                    replacementDocumentStyles = newDocumentStyles ?: documentStyles,
                    expectedPresets = presets,
                    replacementPresets = newPresets ?: presets,
                    attributedTo = documentCause ?: presetsCause,
                )
            }
            .list()
            .filterNotNull()
            .let(rewrites::addAll)
    }

    /**
     * A published version freezes its theme into `resolved_theme`. A font the theme named without a
     * catalog was frozen that way before publishes qualified it, and is resolved at render against
     * the catalog of the template's bound theme -- or, unbound, the tenant's default theme -- which
     * follows that theme when it moves. So when a theme changes catalog, the frozen relative fonts of
     * the versions resolving through it are pinned to the catalog they resolve against today, and
     * their integrity pins rekeyed to match: bytes change, meaning does not. The same exception the
     * moving resource's own versions get, extended to the snapshots that are copies of this theme.
     */
    private fun pinThemeSnapshots(
        handle: Handle,
        tenantKey: TenantKey,
        movingThemes: List<ResourceRelocation>,
        rewrites: MutableList<JsonRewrite>,
    ) {
        for (theme in movingThemes) {
            handle.createQuery(
                """
                SELECT template.catalog_key::text, template.id::text owner_key, versions.variant_key::text,
                       versions.id, versions.resolved_theme::text json
                FROM template_versions versions
                ${templateJoin("versions")}
                JOIN tenants tenant ON tenant.id = template.tenant_key
                LEFT JOIN themes bound
                  ON bound.tenant_key = template.tenant_key AND bound.resource_id = template.theme_resource_id
                LEFT JOIN themes fallback
                  ON fallback.tenant_key = tenant.id AND fallback.resource_id = tenant.default_theme_resource_id
                WHERE versions.tenant_key = :tenantKey AND versions.resolved_theme IS NOT NULL
                  AND COALESCE(bound.catalog_key, fallback.catalog_key) = :themeCatalog
                  AND COALESCE(bound.id, fallback.id) = :themeKey
                ORDER BY template.catalog_key, template.id, versions.variant_key, versions.id
                """,
            )
                .bind("tenantKey", tenantKey)
                .bind("themeCatalog", theme.source.catalogKey)
                .bind("themeKey", theme.source.key)
                .map { rs, _ ->
                    val raw = rs.getString("json")
                    val pinned = pinSnapshotFonts(objectMapper.readTree(raw) as ObjectNode, theme.source.catalogKey)
                        ?: return@map null
                    JsonRewrite.TemplateVersionSnapshot(
                        rs.getString("catalog_key"),
                        rs.getString("owner_key"),
                        rs.getString("variant_key"),
                        rs.getInt("id"),
                        raw,
                        pinned.toString(),
                        theme.source,
                    )
                }
                .list()
                .filterNotNull()
                .let(rewrites::addAll)
        }
    }

    /** The snapshot with its relative fonts pinned to [catalogKey], or null when it has none. */
    private fun pinSnapshotFonts(snapshot: ObjectNode, catalogKey: String): ObjectNode? {
        val pinned = snapshot.deepCopy()
        var changed = false
        fun pin(styles: JsonNode?) {
            val font = styles?.get("fontFamily") as? ObjectNode ?: return
            if (font.hasNonNull("catalogKey") || !font.hasNonNull("slug")) return
            font.put("catalogKey", catalogKey)
            changed = true
        }
        pin(pinned.get("documentStyles"))
        pinned.get("blockStylePresets")?.properties()?.forEach { (_, preset) -> pin(preset) }
        (pinned.get("fontFingerprints") as? ObjectNode)?.let { fingerprints ->
            for ((key, value) in fingerprints.properties().toList()) {
                if (!key.startsWith("/")) continue
                fingerprints.remove(key)
                fingerprints.set("$catalogKey$key", value)
                changed = true
            }
        }
        return pinned.takeIf { changed }
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
     * `catalog.attribute` or left bare. A qualified key names the catalog being vacated, so every
     * relocation re-points it. A bare key resolves tenant-wide by slug, so a move leaves it alone --
     * but a rename would leave it naming a slug nothing answers to. It is re-pointed at the qualified
     * destination when this attribute is the only one answering to that slug; when another attribute
     * shares the slug, the bare key already means that one as much as this, and is left to it.
     *
     * One rewrite per variant for the whole batch, mapping every key at once against the original
     * attributes. Rewriting per relocation would give two relocations touching one variant the same
     * `expected` bytes, so the second update could never match; and mapping keys one relocation at a
     * time would let a member taking an address another is vacating overwrite that member's value.
     *
     * `template_variants` is live mutable configuration rather than versioned content, so every such
     * reference is rewritable and none has to survive on an alias.
     */
    private fun attributeKeyRewrites(
        handle: Handle,
        tenantKey: TenantKey,
        relocations: List<ResourceRelocation>,
    ): List<JsonRewrite> {
        if (relocations.isEmpty()) return emptyList()
        val renamed = mutableMapOf<String, Pair<String, ResourceAddress>>()
        for ((source, target) in relocations) {
            renamed["${source.catalogKey}.${source.key}"] = "${target.catalogKey}.${target.key}" to source
        }
        val renamedSlugs = relocations.filter { it.source.key != it.target.key }
        if (renamedSlugs.isNotEmpty()) {
            val answering = handle.createQuery(
                """
                SELECT id::text slug, COUNT(*) definitions FROM variant_attribute_definitions
                WHERE tenant_key = :tenantKey AND id IN (<slugs>)
                GROUP BY id
                """,
            )
                .bind("tenantKey", tenantKey)
                .bindList("slugs", renamedSlugs.map { it.source.key })
                .map { rs, _ -> rs.getString("slug") to rs.getInt("definitions") }
                .list()
                .toMap()
            for ((source, target) in renamedSlugs) {
                if (answering[source.key] == 1) renamed[source.key] = "${target.catalogKey}.${target.key}" to source
            }
        }

        return handle.createQuery(
            """
            SELECT template.catalog_key::text, template.id::text AS template_key,
                   variants.id::text variant_key, variants.attributes::text
            FROM template_variants variants
            ${templateJoin("variants")}
            WHERE variants.tenant_key = :tenantKey
              AND EXISTS (SELECT 1 FROM jsonb_object_keys(variants.attributes) named WHERE named IN (<keys>))
            ORDER BY template.catalog_key, template.id, variants.id
            """,
        )
            .bind("tenantKey", tenantKey)
            .bindList("keys", renamed.keys.toList())
            .map { rs, _ ->
                val raw = rs.getString("attributes")
                val original = objectMapper.readTree(raw) as ObjectNode
                val moved = objectMapper.createObjectNode()
                var attributedTo: ResourceAddress? = null
                for ((key, value) in original.properties()) {
                    val rename = renamed[key]
                    if (rename != null) attributedTo = attributedTo ?: rename.second
                    moved.set(rename?.first ?: key, value)
                }
                JsonRewrite.VariantAttributes(
                    rs.getString("catalog_key"),
                    rs.getString("template_key"),
                    rs.getString("variant_key"),
                    raw,
                    moved.toString(),
                    attributedTo,
                )
            }
            .list()
    }

    private fun loadTemplateVersions(handle: Handle, tenantKey: TenantKey, owners: Set<ResourceAddress>?): List<JsonOwnerRow> {
        if (owners != null && owners.isEmpty()) return emptyList()
        return handle.createQuery(
            """
            SELECT template.catalog_key::text, template.id::text owner_key,
                   versions.variant_key::text, versions.id, versions.status, versions.template_model::text json
            FROM template_versions versions
            ${templateJoin("versions")}
            WHERE versions.tenant_key = :tenantKey ${ownerFilter(owners, "template.id", "template.catalog_key")}
            ORDER BY template.catalog_key, template.id, versions.variant_key, versions.id
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
            SELECT stencil.catalog_key::text, stencil.id::text owner_key,
                   versions.id, versions.status, versions.content::text json
            FROM stencil_versions versions
            JOIN stencils stencil ON stencil.tenant_key = versions.tenant_key
                                 AND stencil.resource_id = versions.stencil_resource_id
            WHERE versions.tenant_key = :tenantKey ${ownerFilter(owners, "stencil.id", "stencil.catalog_key")}
            ORDER BY stencil.catalog_key, stencil.id, versions.id
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
    private fun ownerFilter(owners: Set<ResourceAddress>?, keyColumn: String, catalogColumn: String = "catalog_key"): String = if (owners == null) {
        ""
    } else {
        owners.indices.joinToString(" OR ", prefix = "AND (", postfix = ")") { "($catalogColumn = :ownerCatalog$it AND $keyColumn = :ownerKey$it)" }
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

    /** A published version's frozen theme snapshot, pinned when the theme it resolves through moves. */
    data class TemplateVersionSnapshot(
        override val catalogKey: String,
        override val ownerKey: String,
        val variantKey: String,
        override val version: Int,
        override val expected: String,
        override val replacement: String,
        override val attributedTo: ResourceAddress?,
    ) : JsonRewrite {
        override val identity get() = "template-snapshot:$catalogKey:$ownerKey:$variantKey:$version"
    }

    /**
     * A theme's two style columns, which carry its font references. Unversioned live configuration,
     * so [version] is unused; either column may be null, and one that did not change is written back
     * as it was.
     */
    data class ThemeStyles(
        override val catalogKey: String,
        override val ownerKey: String,
        val expectedDocumentStyles: String,
        val replacementDocumentStyles: String,
        val expectedPresets: String?,
        val replacementPresets: String?,
        override val attributedTo: ResourceAddress?,
    ) : JsonRewrite {
        override val version = 0
        override val expected get() = "$expectedDocumentStyles|$expectedPresets"
        override val replacement get() = "$replacementDocumentStyles|$replacementPresets"
        override val identity get() = "theme-styles:$catalogKey:$ownerKey"
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
