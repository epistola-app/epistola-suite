// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.model

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.VariantKey

/**
 * A template variant that uses a stencil (contract ADR 0006): the stencil reaches it by reference,
 * so there is nothing to upgrade, only whether the template still fits the stencil as it is.
 *
 * @property problems what no longer fits ([StencilInstances.Finding]s about this stencil, as text);
 *   empty when the template fits.
 */
data class StencilUsageDetail(
    val templateId: TemplateKey,
    val catalogKey: CatalogKey,
    val catalogType: CatalogType = CatalogType.AUTHORED,
    val templateName: String,
    val variantId: VariantKey,
    /** Number of instances of this stencil in this variant's model. */
    val instanceCount: Int,
    val problems: List<String> = emptyList(),
) {
    val fits: Boolean get() = problems.isEmpty()
}
