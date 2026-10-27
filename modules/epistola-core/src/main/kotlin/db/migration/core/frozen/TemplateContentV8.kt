// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package db.migration.core.frozen

import app.epistola.catalog.migration.CatalogDocumentMigration
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * 1.x template content in wire v8, with stencil instances by reference (contract ADR 0006), frozen:
 * what the 2.0 upgrade makes of every template version, stencil and stencil version of 1.x. Pure: it
 * reads the 1.x rows it is given and returns what they become.
 *
 * For every stencil instance in a template version:
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
 * A stencil whose own content reads anything but its parameters and `sys` reads the template's data
 * (`readsTemplateData`). Both detections err towards keeping the 1.x behaviour.
 *
 * A parameter an instance binds, declared by the version it pinned but not by the stencil as it is
 * now, is declared again, optional, on the stencil's working copy and on its latest published
 * version, and reported: a reference to a stencil not declaring it would render it empty.
 *
 * Then every document gets the wire-v8 renames and loses stencil version pins, by the contract's own
 * [CatalogDocumentMigration] (frozen per wire version), and theme styles their font renames.
 *
 * `TemplateContentV8Test` pins it; it is the logic of the 2.0 upgrade as first written
 * (`V20261018200500`), moved here unchanged.
 */
object TemplateContentV8 {

    /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
    const val CHECKSUM: Int = -1619668852

    /** A stencil's working copy: its 1.x draft, else its latest published, else latest archived version. */
    class Stencil(val tenantKey: String, val resourceId: String, val catalogKey: String, val key: String, val content: ObjectNode, val parameterSchema: JsonNode?)

    /** A stored 1.x stencil version, by its number. */
    class StencilVersion(val tenantKey: String, val resourceId: String, val id: Int, val status: String, val content: JsonNode, val parameterSchema: JsonNode?)

    /** A stored 1.x template version of a template of [catalogKey]. */
    class TemplateVersion(val tenantKey: String, val templateResourceId: String, val variantKey: String, val id: Int, val model: JsonNode, val catalogKey: String, val templateKey: String)

    /** What a stencil becomes: its working copy, schema and flag, and its latest published version. */
    class ConvertedStencil(val content: JsonNode, val parameterSchema: JsonNode?, val readsTemplateData: Boolean, val published: ConvertedVersion?)

    /** A stencil version in v8, with the schema it declares. */
    class ConvertedVersion(val content: JsonNode, val parameterSchema: JsonNode?)

    /** The result: every template version's model by `tenant|template resource|variant|id`, every stencil by `tenant|resource`, and what was reported. */
    class Result(val models: Map<String, JsonNode>, val stencils: Map<String, ConvertedStencil>, val report: List<ObjectNode>)

    fun key(tenantKey: String, resourceId: String, variantKey: String, id: Int): String = "$tenantKey|$resourceId|$variantKey|$id"

    fun key(tenantKey: String, resourceId: String): String = "$tenantKey|$resourceId"

    /** Converts [templateVersions] in order (the first instance to bind a parameter names its declaration), then the stencils. */
    fun convert(stencils: List<Stencil>, stencilVersions: List<StencilVersion>, templateVersions: List<TemplateVersion>): Result = Run().convert(stencils, stencilVersions, templateVersions)

    /** A theme's document styles, or a preset's styles, in v8. */
    fun styles(styles: JsonNode): JsonNode = CatalogDocumentMigration.stylesV7ToV8(styles).document

    /** A theme's block style presets, each preset's styles in v8; null unless they are an object. */
    fun presets(presets: JsonNode?): ObjectNode? = (presets?.deepCopy() as? ObjectNode)?.also { all ->
        for (name in all.propertyNames().toList()) {
            val preset = all[name] as? ObjectNode ?: continue
            preset["styles"]?.let { preset.set("styles", styles(it)) }
        }
    }

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

    private class Run {
        private val mapper = CanonicalJson.mapper
        private val report = mutableListOf<ObjectNode>()

        fun convert(stencils: List<Stencil>, stencilVersions: List<StencilVersion>, templateVersions: List<TemplateVersion>): Result {
            val rows = stencils.associate { "${it.tenantKey}|${it.catalogKey}|${it.key}" to StencilRow(it.tenantKey, it.resourceId, it.catalogKey, it.key, it.content.deepCopy()) }
            val versions = stencilVersions.associate { "${it.tenantKey}|${it.resourceId}|${it.id}" to it.content }
            val versionSchemas = stencilVersions.filter { it.parameterSchema != null }.associate { "${it.tenantKey}|${it.resourceId}|${it.id}" to it.parameterSchema!! }

            val models = linkedMapOf<String, JsonNode>()
            for (row in templateVersions) {
                val document = row.model.deepCopy() as? ObjectNode
                if (document == null) {
                    models[key(row.tenantKey, row.templateResourceId, row.variantKey, row.id)] = row.model
                    continue
                }
                val where = "template ${row.catalogKey}/${row.templateKey} variant ${row.variantKey} version ${row.id}"
                convertInstances(document, row.tenantKey, row.catalogKey, rows, versions, versionSchemas, where)
                models[key(row.tenantKey, row.templateResourceId, row.variantKey, row.id)] = CatalogDocumentMigration.templateDocumentV7ToV8(document).document
            }

            val converted = linkedMapOf<String, ConvertedStencil>()
            for (stencil in stencils) {
                val row = rows.getValue("${stencil.tenantKey}|${stencil.catalogKey}|${stencil.key}")
                val content = row.content
                row.passParameters.forEach { name -> passParameters(content, name) }
                val readsTemplateData = readsTemplateData(content, row.aliases)
                if (readsTemplateData) note("stencil ${row.catalogKey}/${row.key}", "reads the template's data; it is marked readsTemplateData")
                val latest = stencilVersions.filter { it.tenantKey == stencil.tenantKey && it.resourceId == stencil.resourceId && it.status == "published" }.maxByOrNull { it.id }
                val added = linkedSetOf<String>()
                val schema = withBound(stencil.parameterSchema, row, added)
                val published = latest?.let { version ->
                    val versionContent = version.content.deepCopy() as? ObjectNode
                    versionContent?.let { c -> row.passParameters.forEach { name -> passParameters(c, name) } }
                    ConvertedVersion(
                        versionContent?.let { CatalogDocumentMigration.templateDocumentV7ToV8(it).document } ?: version.content,
                        withBound(version.parameterSchema, row, added),
                    )
                }
                if (added.isNotEmpty()) {
                    note("stencil ${row.catalogKey}/${row.key}", "templates bind ${added.joinToString(", ") { "'$it'" }}, which it no longer declared; declared again as optional")
                }
                converted[key(stencil.tenantKey, stencil.resourceId)] = ConvertedStencil(CatalogDocumentMigration.templateDocumentV7ToV8(content).document, schema, readsTemplateData, published)
            }
            return Result(models, converted, report)
        }

        /** [schema] with the bound parameters of [stencil] it lacks, each added to [added]; [schema] itself when it lacks none. */
        private fun withBound(schema: JsonNode?, stencil: StencilRow, added: MutableSet<String>): JsonNode? {
            if (stencil.boundDeclarations.isEmpty()) return schema
            val copy = (schema?.deepCopy() as? ObjectNode) ?: mapper.createObjectNode().put("type", "object")
            val properties = (copy["properties"] as? ObjectNode) ?: copy.putObject("properties")
            val missing = stencil.boundDeclarations.filterKeys { !properties.has(it) }
            if (missing.isEmpty()) return schema
            missing.forEach { (name, declaration) -> properties.set(name, declaration.deepCopy()) }
            added += missing.keys
            return copy
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
                // 1.x also kept an instance as a bare reference, without a copy of the stencil (the demo
                // catalog's templates do), and rendered the stencil itself: there is nothing to compare.
                val bare = copyRoots(instance, slots).isEmpty()
                when {
                    key == null -> {
                        noteParametersLost(instance, props, nodes, slots, subject)
                        inline(instance, nodes, slots)
                        note(subject, "names no stencil; its content became plain content")
                    }

                    stencil == null -> {
                        noteParametersLost(instance, props, nodes, slots, subject)
                        inline(instance, nodes, slots)
                        note(subject, "stencil $catalog/$key does not exist; its content became plain content")
                    }

                    pinnedContent == null && !bare -> {
                        noteParametersLost(instance, props, nodes, slots, subject)
                        inline(instance, nodes, slots)
                        note(subject, "stencil $catalog/$key has no version $pinned; its content became plain content")
                    }

                    !bare && shape(copyRoots(instance, slots), nodes, slots) != contentShape(pinnedContent!!) -> {
                        noteParametersLost(instance, props, nodes, slots, subject)
                        inline(instance, nodes, slots)
                        note(subject, "its content differs from stencil $catalog/$key version $pinned; it became plain content")
                    }

                    else -> {
                        if (pinnedContent == null || contentShape(pinnedContent) != contentShape(stencil.content)) {
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

        /**
         * Reports the parameters an instance's copy reads when the copy is about to become plain content:
         * plain content gets no parameters, so each of them renders empty until someone changes it.
         */
        private fun noteParametersLost(instance: ObjectNode, props: ObjectNode, nodes: ObjectNode, slots: ObjectNode, subject: String) {
            val alias = props.text("paramsAlias")?.takeIf { it.isNotBlank() } ?: "params"
            val pattern = Regex("(?<![\\w$.])" + Regex.escape(alias) + "\\.([A-Za-z_][A-Za-z0-9_]*)")
            val read = copyRoots(instance, slots)
                .flatMap { subtreeExpressions(it, nodes, slots) }
                .flatMap { expression -> pattern.findAll(STRING_LITERAL.replace(expression, "\"\"")).map { "$alias.${it.groupValues[1]}" } }
                .distinct()
                .sorted()
            if (read.isNotEmpty()) {
                note(subject, "its copy reads ${read.joinToString(", ")}, which plain content does not get; they render empty until changed")
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

        // ── Helpers ─────────────────────────────────────────────────────────────

        private fun note(subject: String, message: String) {
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
    }

    private val KEYWORDS = setOf("and", "or", "in", "true", "false", "null", "function")
    private val STRING_LITERAL = Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'")
    private val PATH_ROOT = Regex("(?<![\\w$.])([A-Za-z_][A-Za-z0-9_]*)(?!\\s*\\()")
}
