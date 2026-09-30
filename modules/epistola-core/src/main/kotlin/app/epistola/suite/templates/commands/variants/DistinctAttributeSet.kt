// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands.variants

import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.templates.templateAtAddress
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Handle

/**
 * Refuses an attribute set that another variant of the same template already has.
 *
 * Two such variants cannot be told apart by attribute selection: whatever matches one matches the
 * other with the same score, so every generation or preview that reaches them fails with
 * `AMBIGUOUS_VARIANT`. Comparison is JSONB equality, so key order does not matter.
 *
 * An empty set is exempt. A variant without attributes never passes a required criterion and is
 * only reachable by its id, so several of them on one template are harmless.
 *
 * No database constraint backs this: variants stored before it existed may already share a set,
 * and they stay as they are until someone edits them.
 */
internal fun requireDistinctAttributeSet(
    handle: Handle,
    variantId: VariantId,
    attributes: Map<String, String>,
    attributesJson: String,
) {
    if (attributes.isEmpty()) return

    val clash = handle.createQuery(
        """
        SELECT id FROM template_variants
        WHERE tenant_key = :tenantId
          AND template_resource_id = ${templateAtAddress("tenantId", "catalogKey", "templateId")}
          AND id <> :variantId
          AND attributes = :attributes::jsonb
        ORDER BY id
        LIMIT 1
        """,
    )
        .bind("tenantId", variantId.tenantKey)
        .bind("catalogKey", variantId.catalogKey)
        .bind("templateId", variantId.templateKey)
        .bind("variantId", variantId.key)
        .bind("attributes", attributesJson)
        .mapTo(String::class.java)
        .findOne()
        .orElse(null)

    validate("attributes", clash == null) {
        "Variant '$clash' already has these attributes. Attribute selection could not tell the two apart; change at least one value."
    }
}
