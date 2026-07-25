// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.model

import app.epistola.suite.common.ids.VariantKey
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef

/**
 * Creates a minimal default template model for a new variant/version.
 * This produces the structure expected by the editor: a root node with a single empty slot.
 */
fun createDefaultTemplateModel(variantId: VariantKey): TemplateDocument {
    val rootId = "root-${variantId.value}"
    val slotId = "slot-${variantId.value}"
    return TemplateDocument(
        modelVersion = 1,
        root = rootId,
        nodes = mapOf(
            rootId to Node(
                id = rootId,
                type = "root",
                slots = listOf(slotId),
            ),
        ),
        slots = mapOf(
            slotId to Slot(
                id = slotId,
                nodeId = rootId,
                name = "children",
                children = emptyList(),
            ),
        ),
        themeRef = ThemeRef.Inherit,
    )
}
