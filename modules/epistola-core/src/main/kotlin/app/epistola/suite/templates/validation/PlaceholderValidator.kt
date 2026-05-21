package app.epistola.suite.templates.validation

import app.epistola.suite.stencils.PlaceholderNodeKeys
import app.epistola.suite.stencils.StencilNodeKeys
import app.epistola.suite.templates.model.NodeParameterKeys
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import org.springframework.stereotype.Component

/**
 * Structural validator for placeholder and stencil invariants in a TemplateDocument.
 *
 * Independent of [JsonSchemaValidator] — this validates the slot-graph shape, not data.
 * Both classes are wired into the same command handlers.
 *
 * Some checks apply only when validating a stencil-version document; others apply only
 * when validating a template document. The caller chooses by invoking
 * [validateAsStencilDefinition] or [validateAsTemplate] (or the lower-level helpers).
 */
@Component
class PlaceholderValidator {

    private val slugRegex = Regex("^[a-z][a-z0-9-]{0,63}$")
    private val parameterNameRegex = Regex("^[a-z][a-zA-Z0-9_]{0,63}$")

    /**
     * Run all checks that apply to a stencil-version document. Throws [ValidationException]
     * on first violation. Use this from `CreateStencilVersion` and `UpdateStencilDraft`.
     */
    fun validateAsStencilDefinition(doc: TemplateDocument) {
        validatePlaceholderNamesUnique(doc)
        validatePlaceholderNameSlug(doc)
        validateNoNestedPlaceholderDefinition(doc)
        validateStencilBindingShape(doc)
    }

    /**
     * Run all checks that apply to a template document. Throws [ValidationException]
     * on first violation. Use this from `UpdateDraft` and `UpdateStencilInTemplate`.
     */
    fun validateAsTemplate(doc: TemplateDocument) {
        validatePlaceholderNamesUniquePerStencil(doc)
        validatePlaceholderNameSlug(doc)
        validatePlaceholdersHaveStencilAncestor(doc)
        validateNoStencilRecursion(doc)
        validateStencilBindingShape(doc)
    }

    /**
     * `PLACEHOLDER_NAME_DUPLICATE` — within a single stencil-definition document, every
     * placeholder's `props.name` must be unique. Empty/missing names are allowed (caught
     * by slug check). Use [validatePlaceholderNamesUniquePerStencil] for templates, where
     * each embedded stencil owns its own placeholder namespace.
     */
    fun validatePlaceholderNamesUnique(doc: TemplateDocument) {
        val seen = mutableSetOf<String>()
        for (node in doc.nodes.values) {
            if (node.type != PlaceholderNodeKeys.NODE_TYPE) continue
            val name = node.props?.get(PlaceholderNodeKeys.PROP_NAME) as? String ?: continue
            if (name.isEmpty()) continue
            if (!seen.add(name)) {
                throw ValidationException(
                    "content.placeholder.name",
                    "placeholder name '$name' is used more than once",
                    ValidationCode.PLACEHOLDER_NAME_DUPLICATE,
                )
            }
        }
    }

    /**
     * `PLACEHOLDER_NAME_DUPLICATE` — within each stencil instance in a template, placeholder
     * names must be unique. Two stencil instances may both declare a `body` placeholder;
     * they live in independent namespaces. Placeholders without a stencil ancestor are
     * caught separately by [validatePlaceholdersHaveStencilAncestor], so we ignore them
     * here.
     */
    fun validatePlaceholderNamesUniquePerStencil(doc: TemplateDocument) {
        val seenByStencil = mutableMapOf<String, MutableSet<String>>()
        for (node in doc.nodes.values) {
            if (node.type != PlaceholderNodeKeys.NODE_TYPE) continue
            val name = node.props?.get(PlaceholderNodeKeys.PROP_NAME) as? String ?: continue
            if (name.isEmpty()) continue
            val scopeStencil = nearestStencilAncestor(doc, node.id) ?: continue
            val seen = seenByStencil.getOrPut(scopeStencil.id) { mutableSetOf() }
            if (!seen.add(name)) {
                throw ValidationException(
                    "content.placeholder.name",
                    "placeholder name '$name' is used more than once in the same stencil",
                    ValidationCode.PLACEHOLDER_NAME_DUPLICATE,
                )
            }
        }
    }

    private fun nearestStencilAncestor(doc: TemplateDocument, nodeId: String): Node? = ancestorNodes(doc, nodeId).firstOrNull { it.type == StencilNodeKeys.NODE_TYPE }

    /**
     * `PLACEHOLDER_NAME_INVALID` — placeholder names are kebab-case slugs:
     * `^[a-z][a-z0-9-]{0,63}$`. The name must be present.
     */
    fun validatePlaceholderNameSlug(doc: TemplateDocument) {
        for (node in doc.nodes.values) {
            if (node.type != PlaceholderNodeKeys.NODE_TYPE) continue
            val name = node.props?.get(PlaceholderNodeKeys.PROP_NAME) as? String
            if (name == null || !slugRegex.matches(name)) {
                throw ValidationException(
                    "content.placeholder.name",
                    "placeholder name must be a kebab-case slug " +
                        "(^[a-z][a-z0-9-]{0,63}\$); got '${name ?: "<missing>"}'",
                    ValidationCode.PLACEHOLDER_NAME_INVALID,
                )
            }
        }
    }

    /**
     * `PLACEHOLDER_NESTED_DEFINITION` — at the stencil-definition level, a placeholder
     * may not appear inside another placeholder's `fill` slot. (At the template level
     * the same shape is allowed: a fill containing a stencil that itself has placeholders.)
     */
    fun validateNoNestedPlaceholderDefinition(doc: TemplateDocument) {
        for (node in doc.nodes.values) {
            if (node.type != PlaceholderNodeKeys.NODE_TYPE) continue
            val ancestors = ancestorNodes(doc, node.id)
            for (ancestor in ancestors) {
                if (ancestor.type == PlaceholderNodeKeys.NODE_TYPE) {
                    val outerName = ancestor.props?.get(PlaceholderNodeKeys.PROP_NAME) as? String ?: "<unnamed>"
                    val innerName = node.props?.get(PlaceholderNodeKeys.PROP_NAME) as? String ?: "<unnamed>"
                    throw ValidationException(
                        "content.placeholder",
                        "placeholder '$innerName' is nested " +
                            "inside placeholder '$outerName' at the stencil-definition level",
                        ValidationCode.PLACEHOLDER_NESTED_DEFINITION,
                    )
                }
            }
        }
    }

    /**
     * `PLACEHOLDER_OUTSIDE_STENCIL` — at the template level, every placeholder must have
     * a `stencil` node somewhere in its slot-graph ancestor chain. Bare placeholders in
     * a template root or an outer container are illegal.
     */
    fun validatePlaceholdersHaveStencilAncestor(doc: TemplateDocument) {
        for (node in doc.nodes.values) {
            if (node.type != PlaceholderNodeKeys.NODE_TYPE) continue
            val ancestors = ancestorNodes(doc, node.id)
            if (ancestors.none { it.type == StencilNodeKeys.NODE_TYPE }) {
                val name = node.props?.get(PlaceholderNodeKeys.PROP_NAME) as? String ?: "<unnamed>"
                throw ValidationException(
                    "content.placeholder",
                    "placeholder '$name' must be a descendant of a stencil node",
                    ValidationCode.PLACEHOLDER_OUTSIDE_STENCIL,
                )
            }
        }
    }

    /**
     * `STENCIL_RECURSION` — DFS from the root; no stencil node may appear in its own
     * ancestor chain (matched by `props.stencilId`).
     */
    fun validateNoStencilRecursion(doc: TemplateDocument) {
        recurse(doc, doc.root, emptySet())
    }

    private fun recurse(doc: TemplateDocument, nodeId: String, ancestorStencilIds: Set<String>) {
        val node = doc.nodes[nodeId] ?: return
        var ancestors = ancestorStencilIds
        if (node.type == StencilNodeKeys.NODE_TYPE) {
            val sid = node.props?.get(StencilNodeKeys.PROP_STENCIL_ID) as? String
            if (sid != null) {
                if (ancestors.contains(sid)) {
                    throw ValidationException(
                        "content.stencil.stencilId",
                        "stencil '$sid' would contain itself transitively",
                        ValidationCode.STENCIL_RECURSION,
                    )
                }
                ancestors = ancestors + sid
            }
        }
        for (slotId in node.slots) {
            val slot = doc.slots[slotId] ?: continue
            for (childId in slot.children) {
                recurse(doc, childId, ancestors)
            }
        }
    }

    /**
     * `NODE_PARAMETER_BINDINGS_INVALID_SHAPE` / `NODE_PARAMETER_BINDING_NAME_INVALID` /
     * `NODE_PARAMETER_BINDING_EMPTY` — structural shape check for the generic
     * `parameterBindings` prop carried by parametrised nodes. Cross-document checks
     * (does the bound parameter actually exist on the stencil version's schema?)
     * live in [NodeParameterBindingValidator] so that this validator stays
     * standalone and document-only.
     */
    fun validateStencilBindingShape(doc: TemplateDocument) {
        for (node in doc.nodes.values) {
            // Alias check runs whenever the prop is present, even if no bindings are set —
            // a node can carry only `paramsAlias` (e.g. to scope schema defaults under a
            // custom name) and the reserved-name collision still needs catching.
            val alias = node.props?.get(NodeParameterKeys.PROP_PARAMS_ALIAS)
            if (alias != null && alias !is String) {
                throw ValidationException(
                    "content.${node.type}.props.paramsAlias",
                    "paramsAlias must be a string",
                    ValidationCode.NODE_PARAMETER_BINDINGS_INVALID_SHAPE,
                )
            }
            if (alias is String && alias in NodeParameterKeys.RESERVED_ALIASES) {
                throw ValidationException(
                    "content.${node.type}.props.paramsAlias",
                    "paramsAlias '$alias' collides with a " +
                        "reserved scope name (${NodeParameterKeys.RESERVED_ALIASES.joinToString()})",
                    ValidationCode.NODE_PARAMS_ALIAS_RESERVED,
                )
            }

            val rawBindings = node.props?.get(NodeParameterKeys.PROP_PARAMETER_BINDINGS) ?: continue
            if (rawBindings !is Map<*, *>) {
                throw ValidationException(
                    "content.${node.type}.props.parameterBindings",
                    "parameterBindings must be an object " +
                        "of paramName → expression entries",
                    ValidationCode.NODE_PARAMETER_BINDINGS_INVALID_SHAPE,
                )
            }
            for ((rawName, rawExpr) in rawBindings) {
                val name = rawName as? String ?: throw ValidationException(
                    "content.${node.type}.props.parameterBindings",
                    "parameter binding keys must be strings",
                    ValidationCode.NODE_PARAMETER_BINDING_NAME_INVALID,
                )
                if (!parameterNameRegex.matches(name)) {
                    throw ValidationException(
                        "content.${node.type}.props.parameterBindings.$name",
                        "parameter binding name '$name' must match ^[a-z][a-zA-Z0-9_]{0,63}\$",
                        ValidationCode.NODE_PARAMETER_BINDING_NAME_INVALID,
                    )
                }
                if (rawExpr !is String || rawExpr.isBlank()) {
                    throw ValidationException(
                        "content.${node.type}.props.parameterBindings.$name",
                        "parameter binding '$name' must be a non-blank JSONata expression",
                        ValidationCode.NODE_PARAMETER_BINDING_EMPTY,
                    )
                }
            }
        }
    }

    /**
     * Walks slot-graph ancestors of [nodeId] (excluding [nodeId] itself) by scanning every
     * slot that contains the node, then its parent node, and so on. Returns nodes in
     * order from immediate parent up to the root.
     *
     * Costs O(slots) per call which is acceptable for documents with hundreds of nodes;
     * if profiling shows it dominates we can build an index once per validation.
     */
    private fun ancestorNodes(doc: TemplateDocument, nodeId: String): List<Node> {
        val result = mutableListOf<Node>()
        val visited = mutableSetOf<String>()
        var current = nodeId
        while (true) {
            if (!visited.add(current)) break // defensive cycle guard
            val parentSlot = doc.slots.values.firstOrNull { current in it.children } ?: break
            val parent = doc.nodes[parentSlot.nodeId] ?: break
            result.add(parent)
            current = parent.id
        }
        return result
    }
}
