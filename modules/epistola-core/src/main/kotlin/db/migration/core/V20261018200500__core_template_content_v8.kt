// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package db.migration.core

import app.epistola.catalog.migration.CatalogDocumentMigration
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.sql.Connection
import java.sql.ResultSet

/**
 * Stored template content to wire v8, with stencil instances by reference (contract ADR 0006).
 *
 * For every stencil instance in a stored template version:
 * - its copy is the content of the stencil version it pins, apart from fills: it becomes a reference
 *   plus its fills. Pinned to a version that differs from the stencil as it is now, it is reported,
 *   since it now renders the stencil as it is;
 * - it names no stencil, its stencil or pinned version is gone, or its copy differs from that
 *   version: its copy becomes plain content in its place (each placeholder replaced by its fill, else
 *   its default), and it is reported. Nothing that rendered is lost;
 * - a fill that reads the instance's parameters (its alias, `params` unless the instance set one)
 *   makes the stencil's placeholder of that name pass its parameters (`passParameters`), and an alias
 *   other than `params` is kept on the fill (`parametersAs`), so the fill sees what it saw.
 *
 * A stencil whose own content reads anything but its parameters and `sys` declares
 * `reads_template_data`. Both detections err towards keeping the 1.x behaviour.
 *
 * A parameter an instance binds, declared by the version it pinned but not by the stencil as it is
 * now, is declared again, optional, on the working copy and on the latest published version (which
 * the upgrade's first release is cut from), and reported. 1.x rendered the pinned copy with that
 * version's parameters; a reference to a stencil not declaring it would render it empty.
 *
 * Then every stored template document, stencil and stencil version gets the wire-v8 renames and loses
 * stencil version pins, by the contract's own [CatalogDocumentMigration], and theme styles their font renames.
 * What is reported is logged and kept in `app_metadata` under [REPORT_KEY].
 *
 * Works on JSON, not on the application's model, so that later code cannot change what it did.
 */
@Suppress("ClassName")
class V20261018200500__core_template_content_v8 : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapper.builder().build()
    private val report = mutableListOf<ObjectNode>()

    override fun migrate(context: Context) {
        val connection = context.connection
        val stencils = loadStencils(connection)
        val versions = loadStencilVersions(connection)
        val versionSchemas = loadStencilVersionSchemas(connection)

        // Template versions: instances to references, then the v8 renames.
        val rows = query(
            connection,
            """
            SELECT v.tenant_key, v.template_resource_id::text, v.variant_key, v.id, v.template_model::text,
                   c.catalog_key, t.id
            FROM template_versions v
            JOIN document_templates t ON t.tenant_key = v.tenant_key AND t.resource_id = v.template_resource_id
            JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id
            """.trimIndent(),
        ) { rs -> TemplateRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getString(7)) }
        connection.prepareStatement(
            "UPDATE template_versions SET template_model = ?::jsonb " +
                "WHERE tenant_key = ? AND template_resource_id = ?::uuid AND variant_key = ? AND id = ?",
        ).use { update ->
            for (row in rows) {
                val document = mapper.readTree(row.model) as? ObjectNode ?: continue
                val where = "template ${row.catalogKey}/${row.templateKey} variant ${row.variantKey} version ${row.id}"
                convertInstances(document, row.tenantKey, row.catalogKey, stencils, versions, versionSchemas, where)
                val migrated = CatalogDocumentMigration.templateDocumentV7ToV8(document).document
                update.setString(1, mapper.writeValueAsString(migrated))
                update.setString(2, row.tenantKey)
                update.setString(3, row.templateResourceId)
                update.setString(4, row.variantKey)
                update.setInt(5, row.id)
                update.addBatch()
            }
            update.executeBatch()
        }

        // Stencils: their placeholders' passParameters, reads_template_data, and the v8 renames.
        connection.prepareStatement(
            "UPDATE stencils SET content = ?::jsonb, reads_template_data = ? WHERE tenant_key = ? AND resource_id = ?::uuid",
        ).use { update ->
            for (stencil in stencils.values) {
                val content = stencil.content
                stencil.passParameters.forEach { name -> passParameters(content, name) }
                val readsTemplateData = readsTemplateData(content, stencil.aliases)
                if (readsTemplateData) {
                    note("stencil ${stencil.catalogKey}/${stencil.key}", "reads the template's data; it is marked readsTemplateData")
                }
                update.setString(1, mapper.writeValueAsString(CatalogDocumentMigration.templateDocumentV7ToV8(content).document))
                update.setBoolean(2, readsTemplateData)
                update.setString(3, stencil.tenantKey)
                update.setString(4, stencil.resourceId)
                update.addBatch()
            }
            update.executeBatch()
        }

        restoreBoundParameters(connection, stencils.values)

        // Stencil history the same way: the upgrade's first release is cut from each stencil's latest
        // published version (CatalogDependencyBackfillRunner), so that version must be v8 too, with the
        // parameters its placeholders pass to the templates' fills.
        connection.prepareStatement(
            "UPDATE stencil_versions SET content = ?::jsonb WHERE tenant_key = ? AND stencil_resource_id = ?::uuid AND id = ?",
        ).use { update ->
            val byResource = stencils.values.associateBy { "${it.tenantKey}|${it.resourceId}" }
            for ((key, stored) in versions) {
                val (tenantKey, resourceId, id) = key.split("|")
                val content = stored.deepCopy() as? ObjectNode ?: continue
                byResource["$tenantKey|$resourceId"]?.passParameters?.forEach { name -> passParameters(content, name) }
                update.setString(1, mapper.writeValueAsString(CatalogDocumentMigration.templateDocumentV7ToV8(content).document))
                update.setString(2, tenantKey)
                update.setString(3, resourceId)
                update.setInt(4, id.toInt())
                update.addBatch()
            }
            update.executeBatch()
        }

        migrateThemes(connection)

        if (report.isNotEmpty()) {
            connection.prepareStatement(
                "INSERT INTO app_metadata (key, value) VALUES (?, ?::jsonb) " +
                    "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()",
            ).use {
                it.setString(1, REPORT_KEY)
                it.setString(2, mapper.writeValueAsString(mapper.createArrayNode().addAll(report)))
                it.executeUpdate()
            }
        }
        log.info(
            "2.0 template content: {} template versions, {} stencils migrated; {} notice(s) recorded under {}",
            rows.size,
            stencils.size,
            report.size,
            REPORT_KEY,
        )
    }

    // ── Stencil instances ───────────────────────────────────────────────────

    private fun convertInstances(
        document: ObjectNode,
        tenantKey: String,
        templateCatalog: String,
        stencils: Map<String, StencilRow>,
        versions: Map<String, JsonNode>,
        pinnedSchemas: Map<String, JsonNode>,
        where: String,
    ) {
        val nodes = document["nodes"] as? ObjectNode ?: return
        val slots = document["slots"] as? ObjectNode ?: return
        val instances = nodes.propertyNames().toList().sorted()
            .mapNotNull { nodes[it] as? ObjectNode }
            .filter { it.text("type") == "stencil" }
        for (instance in instances) {
            val instanceId = instance.text("id") ?: continue
            if (!nodes.has(instanceId)) continue
            val props = instance["props"] as? ObjectNode ?: mapper.createObjectNode()
            val key = props.text("stencilId")
            val catalog = props.text("catalogKey")?.takeIf { it.isNotBlank() } ?: templateCatalog
            val stencil = key?.let { stencils["$tenantKey|$catalog|$it"] }
            val pinned = (props["draftVersion"] ?: props["version"])?.takeIf { it.isNumber }?.asInt()
            val pinnedContent = if (stencil != null && pinned != null) versions["${stencil.tenantKey}|${stencil.resourceId}|$pinned"] else null
            val subject = "$where, stencil instance $instanceId"
            when {
                key == null -> {
                    inline(instance, nodes, slots)
                    note(subject, "names no stencil; its content became plain content")
                }

                stencil == null -> {
                    inline(instance, nodes, slots)
                    note(subject, "stencil $catalog/$key does not exist; its content became plain content")
                }

                pinnedContent == null -> {
                    inline(instance, nodes, slots)
                    note(subject, "stencil $catalog/$key has no version $pinned; its content became plain content")
                }

                shape(copyRoots(instance, slots), nodes, slots) != contentShape(pinnedContent) -> {
                    inline(instance, nodes, slots)
                    note(subject, "its content differs from stencil $catalog/$key version $pinned; it became plain content")
                }

                else -> {
                    if (contentShape(pinnedContent) != contentShape(stencil.content)) {
                        note(subject, "used version $pinned of stencil $catalog/$key; it now renders the stencil as it is")
                    }
                    val alias = props.text("paramsAlias")?.takeIf { it.isNotBlank() } ?: "params"
                    stencil.aliases += alias
                    // What 1.x rendered with: the instance's snapshot, else its pinned version's schema.
                    val declaredThen = (props["parameterSchemaSnapshot"]?.takeIf { it.isObject } ?: pinnedSchemas["${stencil.tenantKey}|${stencil.resourceId}|$pinned"])
                        ?.get("properties") as? ObjectNode
                    val bound = (props["parameterBindings"] as? ObjectNode)?.propertyNames()
                        ?.filter { props["parameterBindings"][it]?.takeIf { value -> value.isString }?.asString()?.isNotBlank() == true }
                        .orEmpty()
                    for (name in bound) declaredThen?.get(name)?.let { stencil.boundDeclarations.putIfAbsent(name, it) }
                    collapse(instance, nodes, slots, alias, stencil)
                }
            }
        }
    }

    /** The instance as a reference plus one placeholder node per non-empty fill. */
    private fun collapse(instance: ObjectNode, nodes: ObjectNode, slots: ObjectNode, alias: String, stencil: StencilRow) {
        val id = instance.text("id")!!
        val fills = linkedMapOf<String, List<String>>()
        fun findFills(nodeIds: List<String>) {
            for (nodeId in nodeIds) {
                val node = nodes[nodeId] as? ObjectNode ?: continue
                if (node.text("type") == "placeholder") {
                    val name = node["props"]?.text("name") ?: continue
                    val fill = slotsOf(node, slots).firstOrNull { it.text("name") == "fill" }?.childIds().orEmpty()
                    if (fill.isNotEmpty() && name !in fills) fills[name] = fill
                    continue
                }
                slotsOf(node, slots).forEach { findFills(it.childIds()) }
            }
        }
        val roots = copyRoots(instance, slots)
        findFills(roots)
        val keep = fills.values.flatten().toSet()
        roots.forEach { remove(it, nodes, slots, keep) }
        (instance["slots"] as? ArrayNode)?.forEach { slotId -> slots.remove(slotId.asString()) }

        val carriers = mapper.createArrayNode()
        for ((name, children) in fills) {
            val carrierId = "$id:fill:$name"
            val fillSlotId = "$carrierId:fill"
            val carrierProps = mapper.createObjectNode().put("name", name)
            if (children.any { child -> readsAlias(subtreeExpressions(child, nodes, slots), alias) }) {
                stencil.passParameters += name
                if (alias != "params") carrierProps.put("parametersAs", alias)
            }
            val carrier = mapper.createObjectNode().put("id", carrierId).put("type", "placeholder")
            carrier.set("slots", mapper.createArrayNode().add(fillSlotId))
            carrier.set("props", carrierProps)
            nodes.set(carrierId, carrier)
            val fillSlot = mapper.createObjectNode().put("id", fillSlotId).put("nodeId", carrierId).put("name", "fill")
            fillSlot.set("children", mapper.createArrayNode().also { array -> children.forEach(array::add) })
            slots.set(fillSlotId, fillSlot)
            carriers.add(carrierId)
        }
        val childrenSlotId = "$id:children"
        val childrenSlot = mapper.createObjectNode().put("id", childrenSlotId).put("nodeId", id).put("name", "children")
        childrenSlot.set("children", carriers)
        slots.set(childrenSlotId, childrenSlot)
        instance.set("slots", mapper.createArrayNode().add(childrenSlotId))
        (instance["props"] as? ObjectNode)?.apply {
            remove("version")
            remove("draftVersion")
            remove("parameterSchemaSnapshot")
        }
    }

    /** The instance's copy in its place in the document: placeholders become their fill, else their default. */
    private fun inline(instance: ObjectNode, nodes: ObjectNode, slots: ObjectNode) {
        val id = instance.text("id")!!
        fun resolve(nodeIds: List<String>): List<String> = nodeIds.flatMap { nodeId ->
            val node = nodes[nodeId] as? ObjectNode ?: return@flatMap emptyList<String>()
            if (node.text("type") != "placeholder") return@flatMap listOf(nodeId)
            val bySlot = slotsOf(node, slots).associateBy { it.text("name") }
            val fill = bySlot["fill"]?.childIds().orEmpty()
            val chosen = fill.ifEmpty { bySlot["default"]?.childIds().orEmpty() }
            (bySlot.values.flatMap { it.childIds() } - chosen.toSet()).forEach { remove(it, nodes, slots, emptySet()) }
            (node["slots"] as? ArrayNode)?.forEach { slots.remove(it.asString()) }
            nodes.remove(nodeId)
            resolve(chosen)
        }
        fun resolveTree(nodeIds: List<String>) {
            for (nodeId in nodeIds) {
                val node = nodes[nodeId] as? ObjectNode ?: continue
                for (slot in slotsOf(node, slots)) {
                    val resolved = resolve(slot.childIds())
                    slot.set("children", mapper.createArrayNode().also { array -> resolved.forEach(array::add) })
                    resolveTree(resolved)
                }
            }
        }
        val copy = resolve(copyRoots(instance, slots))
        resolveTree(copy)
        // The instance's place in its parent slot takes the copy.
        for (slotId in slots.propertyNames().toList()) {
            val slot = slots[slotId] as? ObjectNode ?: continue
            val children = slot.childIds()
            if (id !in children) continue
            slot.set("children", mapper.createArrayNode().also { array -> children.flatMap { if (it == id) copy else listOf(it) }.forEach(array::add) })
        }
        (instance["slots"] as? ArrayNode)?.forEach { slots.remove(it.asString()) }
        nodes.remove(id)
    }

    // ── Comparing a copy with a stencil version ─────────────────────────────

    private fun copyRoots(instance: ObjectNode, slots: ObjectNode): List<String> = slotsOf(instance, slots).firstOrNull { it.text("name") == "children" }?.childIds().orEmpty()

    private fun contentShape(content: JsonNode): JsonNode {
        val nodes = content["nodes"] as? ObjectNode ?: return mapper.createArrayNode()
        val slots = content["slots"] as? ObjectNode ?: return mapper.createArrayNode()
        val root = nodes[content.text("root") ?: return mapper.createArrayNode()] as? ObjectNode ?: return mapper.createArrayNode()
        return shape(slotsOf(root, slots).flatMap { it.childIds() }, nodes, slots)
    }

    /** A subtree as a value without node and slot ids, placeholder fills left out: what a stencil version defines. */
    private fun shape(roots: List<String>, nodes: ObjectNode, slots: ObjectNode): JsonNode = mapper.createArrayNode().also { out ->
        for (nodeId in roots) {
            val node = nodes[nodeId] as? ObjectNode ?: continue
            val shaped = mapper.createObjectNode()
            for (field in listOf("type", "props", "styles", "stylePreset")) node[field]?.let { shaped.set(field, it) }
            val shapedSlots = mapper.createObjectNode()
            for (slot in slotsOf(node, slots)) {
                val name = slot.text("name") ?: continue
                if (node.text("type") == "placeholder" && name == "fill") continue
                shapedSlots.set(name, shape(slot.childIds(), nodes, slots))
            }
            shaped.set("slots", shapedSlots)
            out.add(shaped)
        }
    }

    // ── Expressions ─────────────────────────────────────────────────────────

    private fun subtreeExpressions(nodeId: String, nodes: ObjectNode, slots: ObjectNode): List<String> {
        val node = nodes[nodeId] as? ObjectNode ?: return emptyList()
        return expressions(node["props"]) + slotsOf(node, slots).flatMap { slot -> slot.childIds().flatMap { subtreeExpressions(it, nodes, slots) } }
    }

    /** Every expression in a value: an `Expression` object's `raw`, or a rich-text expression node's `expression`. */
    private fun expressions(value: JsonNode?): List<String> = when (value) {
        is ObjectNode -> buildList {
            value["raw"]?.takeIf { it.isString && value.has("language") }?.let { add(it.asString()) }
            if (value.text("type") == "expression") value["attrs"]?.get("expression")?.takeIf { it.isString }?.let { add(it.asString()) }
            value.propertyNames().forEach { addAll(expressions(value[it])) }
        }

        is ArrayNode -> value.flatMap { expressions(it) }

        else -> emptyList()
    }

    private fun readsAlias(expressions: List<String>, alias: String): Boolean = expressions.any { alias in roots(it) }

    /** Conservative: a stencil reads the template's data if any expression starts a path with a name not its own. */
    private fun readsTemplateData(content: ObjectNode, aliases: Set<String>): Boolean {
        val nodes = content["nodes"] as? ObjectNode ?: return false
        val own = aliases + setOf("params", "sys")
        return nodes.propertyNames().asSequence()
            .flatMap { expressions((nodes[it] as? ObjectNode)?.get("props")).asSequence() }
            .any { expression -> roots(expression).any { it !in own } }
    }

    /** The names an expression starts a path with: not after `.` or `$`, not a call, not a keyword. */
    private fun roots(expression: String): Set<String> {
        val withoutStrings = STRING_LITERAL.replace(expression, "\"\"")
        return PATH_ROOT.findAll(withoutStrings).map { it.groupValues[1] }.filter { it !in KEYWORDS }.toSet()
    }

    // ── Placeholders that pass parameters ──────────────────────────────────

    private fun passParameters(content: ObjectNode, name: String) {
        val nodes = content["nodes"] as? ObjectNode ?: return
        for (id in nodes.propertyNames()) {
            val node = nodes[id] as? ObjectNode ?: continue
            if (node.text("type") == "placeholder" && node["props"]?.text("name") == name) {
                (node["props"] as ObjectNode).put("passParameters", true)
            }
        }
    }

    // ── Themes ──────────────────────────────────────────────────────────────

    private fun migrateThemes(connection: Connection) {
        val rows = query(connection, "SELECT tenant_key, resource_id::text, document_styles::text, block_style_presets::text FROM themes") { rs ->
            listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
        }
        connection.prepareStatement(
            "UPDATE themes SET document_styles = ?::jsonb, block_style_presets = ?::jsonb WHERE tenant_key = ? AND resource_id = ?::uuid",
        ).use { update ->
            for ((tenantKey, resourceId, styles, presets) in rows) {
                val documentStyles = CatalogDocumentMigration.stylesV7ToV8(mapper.readTree(styles ?: "{}")).document
                val blockPresets = presets?.let { mapper.readTree(it) as? ObjectNode }?.also { all ->
                    for (name in all.propertyNames().toList()) {
                        val preset = all[name] as? ObjectNode ?: continue
                        preset["styles"]?.let { preset.set("styles", CatalogDocumentMigration.stylesV7ToV8(it).document) }
                    }
                }
                update.setString(1, mapper.writeValueAsString(documentStyles))
                update.setString(2, blockPresets?.let { mapper.writeValueAsString(it) })
                update.setString(3, tenantKey)
                update.setString(4, resourceId)
                update.addBatch()
            }
            update.executeBatch()
        }
    }

    // ── Parameters templates bind ───────────────────────────────────────────

    /**
     * Declares again, optional, each parameter instances bind that the stencil's working copy or its
     * latest published version no longer declares, with the declaration the instance's version had.
     */
    private fun restoreBoundParameters(connection: Connection, stencils: Collection<StencilRow>) {
        for (stencil in stencils.filter { it.boundDeclarations.isNotEmpty() }) {
            val added = linkedSetOf<String>()

            /** [schemaJson] with the bound parameters it lacks, or null when it lacks none. */
            fun withBound(schemaJson: String?): String? {
                val schema = (schemaJson?.let(mapper::readTree) as? ObjectNode) ?: mapper.createObjectNode().put("type", "object")
                val properties = (schema["properties"] as? ObjectNode) ?: schema.putObject("properties")
                val missing = stencil.boundDeclarations.filterKeys { !properties.has(it) }
                if (missing.isEmpty()) return null
                missing.forEach { (name, declaration) -> properties.set(name, declaration.deepCopy()) }
                added += missing.keys
                return mapper.writeValueAsString(schema)
            }

            val current = query(
                connection,
                "SELECT parameter_schema::text FROM stencils WHERE tenant_key = ? AND resource_id = ?::uuid",
                stencil.tenantKey,
                stencil.resourceId,
            ) { it.getString(1) }
            withBound(current.firstOrNull())?.let { schema ->
                connection.prepareStatement("UPDATE stencils SET parameter_schema = ?::jsonb WHERE tenant_key = ? AND resource_id = ?::uuid").use {
                    it.setString(1, schema)
                    it.setString(2, stencil.tenantKey)
                    it.setString(3, stencil.resourceId)
                    it.executeUpdate()
                }
            }

            val latest = query(
                connection,
                "SELECT id, parameter_schema::text FROM stencil_versions WHERE tenant_key = ? " +
                    "AND stencil_resource_id = ?::uuid AND status = 'published' ORDER BY id DESC LIMIT 1",
                stencil.tenantKey,
                stencil.resourceId,
            ) { it.getInt(1) to it.getString(2) }.firstOrNull()
            latest?.let { (id, schemaJson) ->
                withBound(schemaJson)?.let { schema ->
                    connection.prepareStatement("UPDATE stencil_versions SET parameter_schema = ?::jsonb WHERE tenant_key = ? AND stencil_resource_id = ?::uuid AND id = ?").use {
                        it.setString(1, schema)
                        it.setString(2, stencil.tenantKey)
                        it.setString(3, stencil.resourceId)
                        it.setInt(4, id)
                        it.executeUpdate()
                    }
                }
            }

            if (added.isNotEmpty()) {
                note(
                    "stencil ${stencil.catalogKey}/${stencil.key}",
                    "templates bind ${added.joinToString(", ") { "'$it'" }}, which it no longer declared; declared again as optional",
                )
            }
        }
    }

    // ── Loading ─────────────────────────────────────────────────────────────

    private fun loadStencils(connection: Connection): Map<String, StencilRow> = query(
        connection,
        "SELECT s.tenant_key, s.resource_id::text, c.catalog_key, s.id, s.content::text " +
            "FROM stencils s JOIN catalogs c ON c.tenant_key = s.tenant_key AND c.catalog_id = s.catalog_id",
    ) { rs -> StencilRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), mapper.readTree(rs.getString(5)) as ObjectNode) }
        .associateBy { "${it.tenantKey}|${it.catalogKey}|${it.key}" }

    private fun loadStencilVersions(connection: Connection): Map<String, JsonNode> = query(
        connection,
        "SELECT tenant_key, stencil_resource_id::text, id, content::text FROM stencil_versions",
    ) { rs -> "${rs.getString(1)}|${rs.getString(2)}|${rs.getInt(3)}" to mapper.readTree(rs.getString(4)) }.toMap()

    private fun loadStencilVersionSchemas(connection: Connection): Map<String, JsonNode> = query(
        connection,
        "SELECT tenant_key, stencil_resource_id::text, id, parameter_schema::text FROM stencil_versions WHERE parameter_schema IS NOT NULL",
    ) { rs -> "${rs.getString(1)}|${rs.getString(2)}|${rs.getInt(3)}" to mapper.readTree(rs.getString(4)) }.toMap()

    private fun <T> query(connection: Connection, sql: String, vararg parameters: String, map: (ResultSet) -> T): List<T> = connection.prepareStatement(sql).use { statement ->
        parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun note(subject: String, message: String) {
        log.warn("2.0 upgrade: {} {}", subject, message)
        report += mapper.createObjectNode().put("subject", subject).put("message", message)
    }

    private fun JsonNode.text(field: String): String? = this[field]?.takeIf { it.isString }?.asString()

    private fun JsonNode.childIds(): List<String> = (this["children"] as? ArrayNode)?.mapNotNull { child -> child.takeIf { it.isString }?.asString() }.orEmpty()

    private fun slotsOf(node: ObjectNode, slots: ObjectNode): List<ObjectNode> = (node["slots"] as? ArrayNode)?.mapNotNull { slots[it.asString()] as? ObjectNode }.orEmpty()

    private fun remove(nodeId: String, nodes: ObjectNode, slots: ObjectNode, keep: Set<String>) {
        if (nodeId in keep) return
        val node = nodes.remove(nodeId) as? ObjectNode ?: return
        (node["slots"] as? ArrayNode)?.forEach { slotId ->
            (slots.remove(slotId.asString()) as? ObjectNode)?.childIds()?.forEach { remove(it, nodes, slots, keep) }
        }
    }

    private data class TemplateRow(
        val tenantKey: String,
        val templateResourceId: String,
        val variantKey: String,
        val id: Int,
        val model: String,
        val catalogKey: String,
        val templateKey: String,
    )

    private class StencilRow(
        val tenantKey: String,
        val resourceId: String,
        val catalogKey: String,
        val key: String,
        val content: ObjectNode,
    ) {
        val passParameters = mutableSetOf<String>()
        val aliases = mutableSetOf<String>()

        /** Parameters instances bind, with the declaration of the version each instance pinned. */
        val boundDeclarations = linkedMapOf<String, JsonNode>()
    }

    companion object {
        const val REPORT_KEY = "upgrade-2.0-template-content"
        private val KEYWORDS = setOf("and", "or", "in", "true", "false", "null", "function")
        private val STRING_LITERAL = Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'")
        private val PATH_ROOT = Regex("(?<![\\w$.])([A-Za-z_][A-Za-z0-9_]*)(?!\\s*\\()")
    }
}
