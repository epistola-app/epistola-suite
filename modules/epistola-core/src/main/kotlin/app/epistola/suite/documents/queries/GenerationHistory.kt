// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.VariantKey

/**
 * Aggregated generation statistics for a tenant.
 */
data class GenerationStats(
    val totalGenerated: Long,
    val inQueue: Long,
    val completed: Long,
    val failed: Long,
    val cancelled: Long,
)

/**
 * Template usage frequency entry.
 */
data class TemplateUsage(
    val catalogKey: CatalogKey,
    val templateKey: TemplateKey,
    val variantKey: VariantKey,
    val count: Long,
    /** The catalog's namespace as the requests recorded it, which tells two catalogs sharing a key apart (ADR 0034); null for requests from before it was. */
    val namespace: String? = null,
) {
    /** How the catalog's pages address it: `namespace/key`, or the key where no namespace was recorded. */
    val catalogSegment: String get() = namespace?.let { "$it/${catalogKey.value}" } ?: catalogKey.value
}
