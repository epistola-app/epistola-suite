// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.UserKey
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
    val catalogKey: CatalogKey,
    val name: String,
    val themeKey: ThemeKey? = null,
    val themeCatalogKey: CatalogKey? = null,
    val pdfaEnabled: Boolean = true,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val createdBy: UserKey? = null,
    val updatedBy: UserKey? = null,
    /** Raised by every change to the template (its name, description, theme, PDF/A or data contract); see [app.epistola.suite.common.Revisions]. */
    val revision: Long = 1,
    /**
     * Plain-text notes for the template's authors and readers: what it is for, design decisions,
     * known gaps. Not rendered. Travels with the template in a catalog (wire v8
     * `TemplateResource.description`); null when it has none.
     */
    val description: String? = null,
)

/**
 * Template with variant summaries for API responses.
 */
data class DocumentTemplateWithVariants(
    val id: TemplateKey,
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val name: String,
    val themeKey: ThemeKey? = null,
    val pdfaEnabled: Boolean = true,
    val variants: List<VariantSummary>,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val createdBy: UserKey? = null,
    val updatedBy: UserKey? = null,
)

/**
 * Whether [this], a template description, fits its ceiling
 * ([app.epistola.suite.validation.FieldLimits.MAX_TEMPLATE_DESCRIPTION_LENGTH], counted in code
 * points as the catalog contract counts it); no description always fits.
 */
fun String?.fitsTemplateDescription(): Boolean = this == null ||
    codePointCount(0, length) <= app.epistola.suite.validation.FieldLimits.MAX_TEMPLATE_DESCRIPTION_LENGTH
