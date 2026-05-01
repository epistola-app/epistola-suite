package app.epistola.suite.templates

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.templates.model.VariantSummary
import java.time.OffsetDateTime

/**
 * Document template entity.
 * Groups related variants. The data contract (schema + examples) is now
 * versioned separately in ContractVersion.
 */
data class DocumentTemplate(
    val id: TemplateKey,
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey = CatalogKey.DEFAULT,
    val catalogType: CatalogType = CatalogType.AUTHORED,
    val name: String,
    val themeKey: ThemeKey? = null,
    val themeCatalogKey: CatalogKey? = null,
    val pdfaEnabled: Boolean = false,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)

/**
 * Template with variant summaries for API responses.
 */
data class DocumentTemplateWithVariants(
    val id: TemplateKey,
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey = CatalogKey.DEFAULT,
    val name: String,
    val themeKey: ThemeKey? = null,
    val pdfaEnabled: Boolean = false,
    val variants: List<VariantSummary>,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)
