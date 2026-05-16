package app.epistola.suite.mcp.dto

import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.queries.TemplateSummary
import java.time.OffsetDateTime

/**
 * Lightweight template record for list views.
 */
data class TemplateSummaryInfo(
    val id: String,
    val catalogId: String,
    val name: String,
    val variantCount: Int,
    val hasDraft: Boolean,
    val publishedVersionCount: Int,
    val updatedAt: OffsetDateTime,
) {
    companion object {
        fun from(summary: TemplateSummary): TemplateSummaryInfo = TemplateSummaryInfo(
            id = summary.id.value,
            catalogId = summary.catalogKey.value,
            name = summary.name,
            variantCount = summary.variantCount,
            hasDraft = summary.hasDraft,
            publishedVersionCount = summary.publishedVersionCount,
            updatedAt = summary.updatedAt,
        )
    }
}

/**
 * Template metadata. Does not include template content — use `get_template_content`
 * for the full editor context (template node/slot graph, data examples, dataModel).
 */
data class TemplateInfo(
    val id: String,
    val catalogId: String,
    val name: String,
    /** Theme key referenced by this template (within `themeCatalogId`), or null if theme is unset. */
    val themeId: String?,
    val themeCatalogId: String?,
    /** Whether the template is configured to render as PDF/A. */
    val pdfaEnabled: Boolean,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    companion object {
        fun from(template: DocumentTemplate): TemplateInfo = TemplateInfo(
            id = template.id.value,
            catalogId = template.catalogKey.value,
            name = template.name,
            themeId = template.themeKey?.value,
            themeCatalogId = template.themeCatalogKey?.value,
            pdfaEnabled = template.pdfaEnabled,
            createdAt = template.createdAt,
            updatedAt = template.updatedAt,
        )
    }
}
