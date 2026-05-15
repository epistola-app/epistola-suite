package app.epistola.suite.templates.validation

import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.TemplateDocument
import org.springframework.stereotype.Component

/**
 * Structural validator for the `pageheader` node-type cardinality and placement.
 *
 * A document may contain at most two `pageheader` nodes, both placed as direct
 * children of the root slot (siblings of the body root). With one header the
 * single node applies to every page; with two, the document-order-first applies
 * to page 1 and the second to pages 2 and onward. The renderer enforces the
 * positional mapping; this validator only guards the shape so the renderer's
 * invariants hold.
 *
 * Independent of [PlaceholderValidator] and [NodeParameterBindingValidator] —
 * wire into the same template-edit command handlers.
 */
@Component
class PageHeaderCardinalityValidator {

    fun validate(doc: TemplateDocument) {
        val pageHeaderIds = doc.nodes.values
            .filter { it.type == PAGE_HEADER_TYPE }
            .map { it.id }
        if (pageHeaderIds.isEmpty()) return

        if (pageHeaderIds.size > 2) {
            throw ValidationException(
                "content.pageheader.cardinality",
                "PAGEHEADER_TOO_MANY: a template may declare at most two 'pageheader' nodes, found ${pageHeaderIds.size}",
            )
        }

        val rootNode = doc.nodes[doc.root]
            ?: throw ValidationException(
                "content.root",
                "PAGEHEADER_ROOT_MISSING: cannot validate pageheader placement without a root node",
            )
        val rootChildren: Set<String> = rootNode.slots
            .asSequence()
            .mapNotNull { doc.slots[it] }
            .flatMap { it.children.asSequence() }
            .toSet()

        for (id in pageHeaderIds) {
            if (id !in rootChildren) {
                throw ValidationException(
                    "content.pageheader.placement",
                    "PAGEHEADER_NOT_AT_ROOT: pageheader node '$id' must be a direct child of the root slot",
                )
            }
        }
    }

    companion object {
        const val PAGE_HEADER_TYPE: String = "pageheader"
    }
}
