// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.model

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey

/**
 * A template version that uses a stencil (contract ADR 0006): the stencil reaches it by reference,
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
    val versionId: VersionKey,
    val versionStatus: String,
    /** Number of instances of this stencil in this template version. */
    val instanceCount: Int,
    val problems: List<String> = emptyList(),
) {
    val fits: Boolean get() = problems.isEmpty()
}
