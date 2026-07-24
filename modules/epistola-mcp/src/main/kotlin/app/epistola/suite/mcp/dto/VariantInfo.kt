// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.templates.model.TemplateVariant
import java.time.OffsetDateTime

/**
 * A template variant — a parallel rendition of a template that shares structure
 * but may differ by attributes (language, brand, audience, etc.). Each variant
 * has its own version history.
 */
data class VariantInfo(
    val id: String,
    val templateId: String,
    val title: String,
    val description: String?,
    /** Free-form attribute map (e.g. `{"language": "en", "brand": "acme"}`). */
    val attributes: Map<String, String>,
    /** True for the variant chosen when no explicit variant is specified at preview time. */
    val isDefault: Boolean,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    companion object {
        fun from(variant: TemplateVariant): VariantInfo = VariantInfo(
            id = variant.id.value,
            templateId = variant.templateKey.value,
            title = variant.title,
            description = variant.description,
            attributes = variant.attributes,
            isDefault = variant.isDefault,
            createdAt = variant.createdAt,
            updatedAt = variant.updatedAt,
        )
    }
}
