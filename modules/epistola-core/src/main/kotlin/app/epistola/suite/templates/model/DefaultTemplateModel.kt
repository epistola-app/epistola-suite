package app.epistola.suite.templates.model

import app.epistola.suite.common.ids.VariantId

/**
 * Creates a minimal default template model for a new variant/version.
 * This produces the structure expected by the editor: a root node with a single empty slot.
 */
fun createDefaultTemplateModel(templateName: String, variantId: VariantId): Map<String, Any> {
    val rootId = "root-${variantId.value}"
    val slotId = "slot-${variantId.value}"
    return mapOf(
        "modelVersion" to 1,
        "root" to rootId,
        "nodes" to mapOf(
            rootId to mapOf(
                "id" to rootId,
                "type" to "root",
                "slots" to listOf(slotId),
            ),
        ),
        "slots" to mapOf(
            slotId to mapOf(
                "id" to slotId,
                "nodeId" to rootId,
                "name" to "children",
                "children" to emptyList<String>(),
            ),
        ),
        "themeRef" to mapOf("type" to "inherit"),
    )
}
