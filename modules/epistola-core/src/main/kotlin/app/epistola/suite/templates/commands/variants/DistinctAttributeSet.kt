// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands.variants

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.templates.StoredTemplate
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
    template: StoredTemplate,
    variantKey: String,
    attributes: Map<String, String>,
) {
    if (attributes.isEmpty()) return

    val clash = template.variants.filter { it.key != variantKey && it.attributes == attributes }.map { it.key }.minOrNull()

    validate("attributes", clash == null) {
        "Variant '$clash' already has these attributes. Attribute selection could not tell the two apart; change at least one value."
    }
}
