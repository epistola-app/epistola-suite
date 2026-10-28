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
    /** The namespace of its catalog (ADR 0034, section 9). */
    val namespace: String = "",
    /** The namespace of [themeCatalogKey]'s catalog. */
    val themeCatalogNamespace: String? = null,
) {
    /** Its catalog's address. */
    val catalogId: app.epistola.suite.common.ids.CatalogId get() = app.epistola.suite.common.ids.CatalogId(namespace, catalogKey, app.epistola.suite.common.ids.TenantId(tenantKey))

    /** The catalog of its theme, when it names one. */
    val themeCatalogId: app.epistola.suite.common.ids.CatalogId? get() = themeCatalogKey?.let { app.epistola.suite.common.ids.CatalogId(requireNotNull(themeCatalogNamespace), it, app.epistola.suite.common.ids.TenantId(tenantKey)) }
}

/**
 * Whether [this], a template description, fits its ceiling
 * ([app.epistola.suite.validation.FieldLimits.MAX_TEMPLATE_DESCRIPTION_LENGTH], counted in code
 * points as the catalog contract counts it); no description always fits.
 */
fun String?.fitsTemplateDescription(): Boolean = this == null ||
    codePointCount(0, length) <= app.epistola.suite.validation.FieldLimits.MAX_TEMPLATE_DESCRIPTION_LENGTH
