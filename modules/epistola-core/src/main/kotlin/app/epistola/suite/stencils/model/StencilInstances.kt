// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.model

import app.epistola.catalog.validation.TemplateValidationLimits
import app.epistola.suite.stencils.PlaceholderNodeKeys
import app.epistola.suite.stencils.StencilNodeKeys
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.NodeParameterKeys
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument

/**
 * A stencil instance as a template stores it, and as the editor and the renderer see it.
 *
 * A template stores an instance **by reference**: the stencil node names the stencil (key, and
 * catalog when it is not the template's own) and holds what the template supplies — its parameter
 * bindings, and in its `children` slot one placeholder node per placeholder it fills, carrying the
 * fill. The stencil's own content is not stored in the template, so a change to the stencil reaches
 * every template that uses it with nothing to update.
 *
 * - [collapse] turns instances with the stencil's content inlined (what the editor edits) back into
 *   the stored form.
 * - [expand] inlines each stencil's content for the editor and the renderer, puts each fill into
 *   its placeholder by name, and reports where the document no longer fits a stencil it uses.
 *
 * Stencils may contain stencil instances. [expand] inlines them too: a reference without a catalog
 * means the catalog of the stencil it sits in, a stencil reaching itself is a [Problem.RECURSION],
 * and nesting stops at [TemplateValidationLimits.MAX_STENCIL_NESTING_DEPTH].
 *
 * Expanded node and slot IDs are derived from the instance and the stencil's own IDs, so expanding
 * the same document twice gives the same IDs; fill content keeps the IDs the template gave it.
 */
object StencilInstances {

    /** A stencil's content and parameter schema, as an instance resolves it. */
    data class Content(
        val document: TemplateDocument,
        val parameterSchema: Map<String, Any?>?,
    )

    /** Resolves the stencil [stencilKey] of catalog [catalogKey], or null when there is none. */
    fun interface Resolver {
        fun resolve(stencilKey: String, catalogKey: String): Content?
    }

    enum class Problem {
        /** No stencil with that key in the catalog the instance names. */
        STENCIL_NOT_FOUND,

        /** The stencil contains, directly or further down, an instance of itself. */
        RECURSION,

        /** Stencils are nested deeper than [TemplateValidationLimits.MAX_STENCIL_NESTING_DEPTH]. */
        DEPTH_EXCEEDED,

        /** The document fills a placeholder the stencil does not have. */
        UNKNOWN_PLACEHOLDER,

        /** The stencil requires a parameter the document neither binds nor gets a default for. */
        UNBOUND_REQUIRED_PARAMETER,

        /** The document binds a parameter the stencil does not declare. */
        UNKNOWN_PARAMETER,
    }

    /**
     * Where a document no longer fits a stencil it uses. [usedIn] names the stencil whose content
     * holds the instance, or is null for an instance in the document itself; [name] is the
     * placeholder or parameter.
     */
    data class Finding(
        val instanceNodeId: String,
        val stencilKey: String,
        val catalogKey: String,
        val usedIn: StencilRef?,
        val problem: Problem,
        val name: String? = null,
    ) {
        /** Only an unknown binding is harmless: it is ignored when rendering. */
        val blocking: Boolean get() = problem != Problem.UNKNOWN_PARAMETER
    }

    data class StencilRef(val stencilKey: String, val catalogKey: String)

    data class Expanded(
        val document: TemplateDocument,
        val findings: List<Finding>,
    )

    /** Props an instance carries only while expanded, or carried as a version pin before 2.0. */
    private val TRANSIENT_PROPS = setOf(
        StencilNodeKeys.PROP_VERSION,
        StencilNodeKeys.PROP_DRAFT_VERSION,
        StencilNodeKeys.PROP_PARAMETER_SCHEMA_SNAPSHOT,
    )

    /**
     * The stored form of [document]: every stencil instance reachable from the root keeps only its
     * reference, its bindings and its non-empty fills; instances inside a fill are collapsed too.
     * Idempotent.
     */
    fun collapse(document: TemplateDocument): TemplateDocument {
        if (document.nodes.values.none { it.type == StencilNodeKeys.NODE_TYPE }) return document
        val nodes = document.nodes.toMutableMap()
        val slots = document.slots.toMutableMap()

        fun walk(nodeId: String) {
            val node = nodes[nodeId] ?: return
            if (node.type != StencilNodeKeys.NODE_TYPE) {
                node.slots.mapNotNull(slots::get).forEach { slot -> slot.children.forEach(::walk) }
                return
            }
            val fills = collapseInstance(node, nodes, slots)
            // Template content inside a fill may hold instances of its own.
            fills.forEach { fill -> fill.children.forEach(::walk) }
        }
        walk(document.root)
        return document.copy(nodes = nodes, slots = slots)
    }

    private fun collapseInstance(instance: Node, nodes: MutableMap<String, Node>, slots: MutableMap<String, Slot>): Collection<Fill> {
        val childrenSlot = instance.slots.mapNotNull(slots::get).firstOrNull { it.name == StencilNodeKeys.SLOT_CHILDREN }
        val fills = childrenSlot?.let { fillsByName(it.children, nodes, slots) }.orEmpty().toSortedMap()

        // Drop the inlined content, keeping each fill's subtree as it is.
        val keep = fills.values.flatMap { it.children }.toSet()
        childrenSlot?.children?.forEach { removeSubtree(it, nodes, slots, keep) }
        instance.slots.filter { it != childrenSlot?.id }.forEach { slotId ->
            slots.remove(slotId)?.children?.forEach { removeSubtree(it, nodes, slots, keep) }
        }

        // The stored placeholder nodes get IDs from the instance and the name, so the stored form is
        // the same however it was reached — editing and saving an unchanged template changes nothing.
        val placeholderIds = fills.map { (name, fill) ->
            val placeholderId = "${instance.id}:fill:$name"
            val fillSlotId = "$placeholderId:${PlaceholderNodeKeys.SLOT_FILL}"
            nodes[placeholderId] = Node(
                id = placeholderId,
                type = PlaceholderNodeKeys.NODE_TYPE,
                slots = listOf(fillSlotId),
                props = mapOf(PlaceholderNodeKeys.PROP_NAME to name),
            )
            slots[fillSlotId] = Slot(id = fillSlotId, nodeId = placeholderId, name = PlaceholderNodeKeys.SLOT_FILL, children = fill.children)
            placeholderId
        }
        val childrenSlotId = "${instance.id}:${StencilNodeKeys.SLOT_CHILDREN}"
        childrenSlot?.let { slots.remove(it.id) }
        slots[childrenSlotId] = Slot(id = childrenSlotId, nodeId = instance.id, name = StencilNodeKeys.SLOT_CHILDREN, children = placeholderIds)
        nodes[instance.id] = instance.copy(
            slots = listOf(childrenSlotId),
            props = instance.props.orEmpty().filterKeys { it !in TRANSIENT_PROPS }.ifEmpty { null },
        )
        return fills.values
    }

    /**
     * [document], owned by catalog [catalogKey], with every stencil instance's content inlined from
     * [resolver], and every way it no longer fits the stencils it uses. An instance whose stencil
     * cannot be inlined keeps its fills and renders nothing of the stencil.
     */
    fun expand(document: TemplateDocument, catalogKey: String, resolver: Resolver): Expanded {
        val stored = collapse(document)
        if (stored.nodes.values.none { it.type == StencilNodeKeys.NODE_TYPE }) return Expanded(stored, emptyList())
        val nodes = stored.nodes.toMutableMap()
        val slots = stored.slots.toMutableMap()
        val findings = mutableListOf<Finding>()
        // A fill is expanded in its own context before it moves into the inlined stencil; walking the
        // stencil afterwards reaches it again and must leave it alone.
        val expanded = mutableSetOf<String>()

        /** Expands the instances below [nodeId]; [path] is the stencils already being inlined. */
        fun walk(nodeId: String, contextCatalog: String, path: List<StencilRef>) {
            val node = nodes[nodeId] ?: return
            if (node.type != StencilNodeKeys.NODE_TYPE) {
                node.slots.mapNotNull(slots::get).forEach { slot -> slot.children.forEach { walk(it, contextCatalog, path) } }
                return
            }
            if (!expanded.add(node.id)) return
            val props = node.props.orEmpty()
            val stencilKey = props[StencilNodeKeys.PROP_STENCIL_ID] as? String ?: return
            val ref = StencilRef(stencilKey, (props[StencilNodeKeys.PROP_CATALOG_KEY] as? String)?.takeIf { it.isNotBlank() } ?: contextCatalog)
            fun finding(problem: Problem, name: String? = null) = Finding(node.id, ref.stencilKey, ref.catalogKey, path.lastOrNull(), problem, name)

            val childrenSlotId = node.slots.single()
            val fills = fillsByName(slots.getValue(childrenSlotId).children, nodes, slots)
            // Fills are the document's content, in the context it is in: expand instances there first.
            fills.values.forEach { fill -> fill.children.forEach { walk(it, contextCatalog, path) } }

            val content = when {
                ref in path -> null.also { findings += finding(Problem.RECURSION) }
                path.size >= TemplateValidationLimits.MAX_STENCIL_NESTING_DEPTH -> null.also { findings += finding(Problem.DEPTH_EXCEEDED) }
                else -> resolver.resolve(ref.stencilKey, ref.catalogKey) ?: null.also { findings += finding(Problem.STENCIL_NOT_FOUND) }
            } ?: return

            val inlined = inline(node.id, content.document)
            slots.getValue(childrenSlotId).children.forEach { placeholderId -> nodes.remove(placeholderId)?.slots?.forEach(slots::remove) }
            nodes.putAll(inlined.nodes)
            slots.putAll(inlined.slots)
            slots[childrenSlotId] = slots.getValue(childrenSlotId).copy(children = inlined.rootChildren)

            // Each fill goes into the outermost placeholder of its name; inner stencils' placeholders
            // are the outer stencil's to fill, not the document's.
            val placeholders = topLevelPlaceholders(inlined.rootChildren, nodes, slots)
            placeholders.forEach { (name, placeholder) ->
                val fill = fills[name] ?: return@forEach
                val fillSlot = placeholder.slots.mapNotNull(slots::get).firstOrNull { it.name == PlaceholderNodeKeys.SLOT_FILL } ?: return@forEach
                fillSlot.children.forEach { removeSubtree(it, nodes, slots, emptySet()) }
                slots[fillSlot.id] = fillSlot.copy(children = fill.children)
            }
            (fills.keys - placeholders.keys).sorted().forEach { name ->
                findings += finding(Problem.UNKNOWN_PLACEHOLDER, name)
                // An unknown fill is not rendered.
                fills.getValue(name).children.forEach { removeSubtree(it, nodes, slots, emptySet()) }
            }
            findings += parameterFindings(props, content.parameterSchema) { problem, name -> finding(problem, name) }
            nodes[node.id] = node.copy(
                props = content.parameterSchema?.let { props + (StencilNodeKeys.PROP_PARAMETER_SCHEMA_SNAPSHOT to it) } ?: props,
            )

            // The stencil's own instances resolve in the stencil's catalog, and are its to fit.
            inlined.rootChildren.forEach { walk(it, ref.catalogKey, path + ref) }
        }
        walk(stored.root, catalogKey, emptyList())
        return Expanded(stored.copy(nodes = nodes, slots = slots), findings)
    }

    private fun parameterFindings(
        props: Map<String, Any?>,
        schema: Map<String, Any?>?,
        finding: (Problem, String) -> Finding,
    ): List<Finding> {
        val bindings = (props[NodeParameterKeys.PROP_PARAMETER_BINDINGS] as? Map<*, *>)?.keys?.filterIsInstance<String>().orEmpty().toSet()
        val properties = schema?.get("properties") as? Map<*, *> ?: emptyMap<String, Any?>()
        val declared = properties.keys.filterIsInstance<String>().toSet()
        val required = (schema?.get("required") as? List<*>)?.filterIsInstance<String>().orEmpty()
        val unbound = required
            .filter { it !in bindings && (properties[it] as? Map<*, *>)?.containsKey("default") != true }
            .sorted()
            .map { finding(Problem.UNBOUND_REQUIRED_PARAMETER, it) }
        val unknown = (bindings - declared).sorted().map { finding(Problem.UNKNOWN_PARAMETER, it) }
        return unbound + unknown
    }

    /** A fill: the top-level nodes of a placeholder's `fill` slot. */
    private data class Fill(val children: List<String>)

    /** Non-empty fills of the outermost placeholders under [roots], by name; the first of a name wins. */
    private fun fillsByName(roots: List<String>, nodes: Map<String, Node>, slots: Map<String, Slot>): Map<String, Fill> = topLevelPlaceholders(roots, nodes, slots)
        .mapNotNull { (name, node) ->
            val fillSlot = node.slots.mapNotNull(slots::get).firstOrNull { it.name == PlaceholderNodeKeys.SLOT_FILL }
            fillSlot?.takeIf { it.children.isNotEmpty() }?.let { name to Fill(it.children) }
        }
        .toMap()

    /**
     * The placeholders under [roots] that belong to this level, by name (first wins): not inside
     * another placeholder. A nested stencil instance's own placeholders are its stencil's, but the
     * fills the instance carries are this level's content, so they are searched.
     */
    private fun topLevelPlaceholders(roots: List<String>, nodes: Map<String, Node>, slots: Map<String, Slot>): Map<String, Node> {
        val result = linkedMapOf<String, Node>()
        fun fillSlotsOf(placeholder: Node) = placeholder.slots.mapNotNull(slots::get).filter { it.name == PlaceholderNodeKeys.SLOT_FILL }
        fun visit(nodeId: String) {
            val node = nodes[nodeId] ?: return
            when (node.type) {
                PlaceholderNodeKeys.NODE_TYPE -> (node.props?.get(PlaceholderNodeKeys.PROP_NAME) as? String)?.let { result.putIfAbsent(it, node) }

                StencilNodeKeys.NODE_TYPE -> node.slots.mapNotNull(slots::get).flatMap { it.children }.mapNotNull(nodes::get)
                    .forEach { carrier -> fillSlotsOf(carrier).flatMap { it.children }.forEach(::visit) }

                else -> node.slots.mapNotNull(slots::get).forEach { slot -> slot.children.forEach(::visit) }
            }
        }
        roots.forEach(::visit)
        return result
    }

    private class Inlined(val rootChildren: List<String>, val nodes: Map<String, Node>, val slots: Map<String, Slot>)

    /** The stencil's content below its root, re-keyed under [instanceId]. */
    private fun inline(instanceId: String, content: TemplateDocument): Inlined {
        fun nodeId(id: String) = "$instanceId:$id"
        fun slotId(id: String) = "$instanceId:s:$id"
        val rootSlots = content.nodes[content.root]?.slots.orEmpty().toSet()
        val nodes = content.nodes.values
            .filter { it.id != content.root }
            .associate { nodeId(it.id) to it.copy(id = nodeId(it.id), slots = it.slots.map(::slotId)) }
        val slots = content.slots.values
            .filter { it.id !in rootSlots }
            .associate { slotId(it.id) to it.copy(id = slotId(it.id), nodeId = nodeId(it.nodeId), children = it.children.map(::nodeId)) }
        val rootChildren = rootSlots.flatMap { content.slots[it]?.children.orEmpty() }.map(::nodeId)
        return Inlined(rootChildren, nodes, slots)
    }

    private fun removeSubtree(id: String, nodes: MutableMap<String, Node>, slots: MutableMap<String, Slot>, keep: Set<String>) {
        if (id in keep) return
        val node = nodes.remove(id) ?: return
        node.slots.forEach { slotId -> slots.remove(slotId)?.children?.forEach { removeSubtree(it, nodes, slots, keep) } }
    }
}
