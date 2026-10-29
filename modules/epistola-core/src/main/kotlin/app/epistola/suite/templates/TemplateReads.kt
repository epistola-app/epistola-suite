// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.stencils.qualifiedIn
import app.epistola.template.model.TemplateDocument
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/*
 * The one read interface for templates and their variants (ADR 0033, step 7a): the views
 * `current_templates` and `current_template_variants`, which answer a draft's rows or, for a message
 * that targets a release, the release's content. Readers name a template through [templateIn] and
 * map through the column lists here, so a draft and a release read the same way.
 */

/**
 * The identity of the template at `(:catalogParam, :keyParam)` in the version a caller means, a
 * draft's or a release's; NULL when there is none, which matches no row.
 */
fun templateIn(tenantParam: String, catalogParam: String, keyParam: String): String = "(SELECT resource_id FROM current_templates WHERE tenant_key = :$tenantParam AND catalog_id = ${catalogAt(tenantParam, catalogParam)} AND id = :$keyParam)"

/**
 * The columns [DocumentTemplate] maps from, for a query over `current_templates dt`. Catalog keys and
 * namespaces are looked up row by row rather than joined (ThemePlanStabilityTest).
 */
const val TEMPLATE_COLUMNS: String =
    "dt.id, dt.tenant_key, " +
        "(SELECT c.catalog_key FROM catalogs c WHERE c.tenant_key = dt.tenant_key AND c.catalog_id = dt.catalog_id) AS catalog_key, " +
        "(SELECT c.namespace FROM catalogs c WHERE c.tenant_key = dt.tenant_key AND c.catalog_id = dt.catalog_id) AS namespace, " +
        "dt.name, dt.theme_key, " +
        "(SELECT c.catalog_key FROM catalogs c WHERE c.tenant_key = dt.tenant_key AND c.catalog_id = dt.theme_catalog_id) AS theme_catalog_key, " +
        "(SELECT c.namespace FROM catalogs c WHERE c.tenant_key = dt.tenant_key AND c.catalog_id = dt.theme_catalog_id) AS theme_catalog_namespace, " +
        "dt.pdfa_enabled, dt.description, dt.created_at, dt.updated_at, dt.created_by, dt.updated_by, dt.revision"

/**
 * The columns [app.epistola.suite.templates.model.TemplateVariant] maps from, for a query over
 * `current_template_variants tv` already filtered to the template bound as `:templateId`.
 */
const val VARIANT_COLUMNS: String =
    "tv.id, tv.tenant_key, " +
        "(SELECT c.catalog_key FROM catalogs c WHERE c.tenant_key = tv.tenant_key AND c.catalog_id = tv.catalog_id) AS catalog_key, " +
        "CAST(:templateId AS TEXT) AS template_key, tv.title, tv.description, tv.attributes, tv.is_default, " +
        "tv.created_at, tv.updated_at, tv.created_by, tv.updated_by, tv.revision"

/** The order a template's variants are listed in: its default first, then as its content lists them (as they were added). */
const val VARIANT_ORDER: String = "ORDER BY tv.is_default DESC, tv.position ASC"

/**
 * Template models as they are stored and as they are read (ADR 0033).
 *
 * A model is stored in its released form, own-catalog references relative and without name
 * qualifiers, so a draft and the release cut from it hold the same model. Everything that reads one
 * (the editor, rendering, relocation, usages) has always seen those references named with the
 * template's catalog, so a read qualifies them again.
 */
object TemplateModels {
    /**
     * [model] of a template of [catalogKey], as it is stored: its released form, written as the
     * release writes it (through [TemplateDocument], so a field it clears is null, as there).
     */
    fun stored(model: TemplateDocument, catalogKey: String, objectMapper: ObjectMapper): JsonNode = objectMapper.valueToTree(
        objectMapper.treeToValue(CatalogContentBuilder.Outbound(catalogKey).content(objectMapper.valueToTree(model)), TemplateDocument::class.java),
    )

    /** A stored [model] of a template of [catalogKey], as it is read. */
    fun read(model: TemplateDocument, catalogKey: String, objectMapper: ObjectMapper): TemplateDocument = model.qualifiedIn(catalogKey, objectMapper)

    /** A stored [json] model of a template of [catalogKey], as it is read. */
    fun read(json: String, catalogKey: String, objectMapper: ObjectMapper): TemplateDocument = read(objectMapper.readValue(json, TemplateDocument::class.java), catalogKey, objectMapper)
}

/** Template [id] in the version the handle means, a draft's or a release's; null when there is none. */
fun documentTemplate(handle: org.jdbi.v3.core.Handle, id: app.epistola.suite.common.ids.TemplateId): DocumentTemplate? = handle
    .createQuery("SELECT $TEMPLATE_COLUMNS FROM current_templates dt WHERE dt.tenant_key = :tenantId AND dt.catalog_id = ${catalogAt("tenantId", "catalogKey")} AND dt.id = :id")
    .bind("id", id.key.value)
    .bind("tenantId", id.tenantKey)
    .bindCatalog("catalogKey", id.catalogId)
    .mapTo(DocumentTemplate::class.java)
    .findOne()
    .orElse(null)

/** Variant [id] in the version the handle means, a draft's or a release's; null when there is none. */
fun templateVariant(handle: org.jdbi.v3.core.Handle, id: app.epistola.suite.common.ids.VariantId): app.epistola.suite.templates.model.TemplateVariant? = handle
    .createQuery(
        """
        SELECT $VARIANT_COLUMNS FROM current_template_variants tv
        WHERE tv.id = :variantId AND tv.tenant_key = :tenantId AND tv.catalog_id = ${catalogAt("tenantId", "catalogKey")}
          AND tv.template_resource_id = ${templateIn("tenantId", "catalogKey", "templateId")}
        """,
    )
    .bind("variantId", id.key.value)
    .bind("templateId", id.templateKey.value)
    .bind("tenantId", id.tenantKey)
    .bindCatalog("catalogKey", id.catalogId)
    .mapTo(app.epistola.suite.templates.model.TemplateVariant::class.java)
    .findOne()
    .orElse(null)
