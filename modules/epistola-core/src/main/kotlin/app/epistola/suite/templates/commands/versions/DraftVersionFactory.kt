// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands.versions

import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.templates.analysis.TemplatePathExtractor
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.TemplateVersion
import app.epistola.suite.templates.model.createDefaultTemplateModel
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Creates (or returns) a variant's draft version **within a caller-supplied
 * transaction**.
 *
 * Extracted from [CreateVersion] so operations that need to open a draft and
 * then mutate it (e.g. `UpdateStencilInTemplate`) can do both atomically in one
 * transaction instead of dispatching `CreateVersion` as a separate command (and
 * thus a separate transaction). [CreateVersion] now delegates here too, so the
 * draft-creation behaviour stays in one place.
 */
@Component
class DraftVersionFactory(
    private val objectMapper: ObjectMapper,
    private val pathExtractor: TemplatePathExtractor,
) {
    /**
     * Ensures [variantId] has a draft, using [handle]'s transaction.
     *
     * Idempotent: if a draft already exists it is returned unchanged. Otherwise a
     * new draft is created from [templateModel] when provided, else a copy of the
     * latest published version's model, else a default empty model.
     *
     * Returns null if the variant does not exist. Throws if the version limit is
     * reached or no contract version exists for the template.
     */
    fun ensureDraft(
        handle: Handle,
        variantId: VariantId,
        templateModel: TemplateDocument? = null,
    ): TemplateVersion? {
        val auditUser = currentUserIdOrNull()?.value

        // Verify the variant exists and get the template name for the default model.
        val templateInfo = handle.createQuery(
            """
            SELECT dt.name as template_name
            FROM template_variants tv
            JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.catalog_key = tv.catalog_key AND dt.id = tv.template_key
            WHERE tv.tenant_key = :tenantId AND tv.catalog_key = :catalogKey AND tv.id = :variantId
              AND tv.template_key = :templateId
            """,
        )
            .bind("variantId", variantId.key)
            .bind("templateId", variantId.templateKey)
            .bind("tenantId", variantId.tenantKey)
            .bind("catalogKey", variantId.catalogKey)
            .mapToMap()
            .findOne()
            .orElse(null) ?: return null

        val templateName = templateInfo["template_name"] as String

        // Idempotent: return the existing draft if there is one.
        val existingDraft = handle.createQuery(
            """
            SELECT *
            FROM template_versions
            WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND variant_key = :variantId
              AND template_key = :templateId AND status = 'draft'
            """,
        )
            .bind("tenantId", variantId.tenantKey)
            .bind("catalogKey", variantId.catalogKey)
            .bind("variantId", variantId.key)
            .bind("templateId", variantId.templateKey)
            .mapTo<TemplateVersion>()
            .findOne()
            .orElse(null)

        if (existingDraft != null) return existingDraft

        val nextVersionId = handle.createQuery(
            """
            SELECT COALESCE(MAX(id), 0) + 1 as next_id
            FROM template_versions
            WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND variant_key = :variantId
              AND template_key = :templateId
            """,
        )
            .bind("tenantId", variantId.tenantKey)
            .bind("catalogKey", variantId.catalogKey)
            .bind("variantId", variantId.key)
            .bind("templateId", variantId.templateKey)
            .mapTo(Int::class.java)
            .one()

        require(nextVersionId <= VersionKey.MAX_VERSION) {
            "Maximum version limit (${VersionKey.MAX_VERSION}) reached for variant ${variantId.key}"
        }

        val versionId = VersionKey.of(nextVersionId)

        // Use the provided model, or copy the latest published model, or a default.
        val latestPublishedModelJson = if (templateModel == null) {
            handle.createQuery(
                """
                SELECT template_model::text FROM template_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND variant_key = :variantKey
                  AND status = 'published'
                ORDER BY id DESC LIMIT 1
                """,
            )
                .bind("tenantKey", variantId.tenantKey)
                .bind("catalogKey", variantId.catalogKey)
                .bind("templateKey", variantId.templateKey)
                .bind("variantKey", variantId.key)
                .mapTo(String::class.java)
                .findOne()
                .orElse(null)
        } else {
            null
        }

        val modelToSave = templateModel
            ?: latestPublishedModelJson?.let { objectMapper.readValue(it, TemplateDocument::class.java) }
            ?: createDefaultTemplateModel(templateName, variantId.key)
        val templateModelJson = if (latestPublishedModelJson != null && templateModel == null) {
            latestPublishedModelJson // Reuse the JSON string directly
        } else {
            objectMapper.writeValueAsString(modelToSave)
        }
        // createDefaultTemplateModel returns a plain Map (no contract paths); only a
        // real TemplateDocument has referenced paths to extract.
        val referencedPaths = if (modelToSave is TemplateDocument) pathExtractor.extractReferencedPaths(modelToSave) else emptySet()
        val referencedPathsJson = objectMapper.writeValueAsString(referencedPaths)

        // Resolve contract version: prefer draft (user is editing), fall back to published.
        val contractVersionId = handle.createQuery(
            """
            SELECT id FROM contract_versions
            WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND template_key = :templateKey
            ORDER BY CASE status WHEN 'draft' THEN 0 ELSE 1 END, id DESC
            LIMIT 1
            """,
        )
            .bind("tenantKey", variantId.tenantKey)
            .bind("catalogKey", variantId.catalogKey)
            .bind("templateKey", variantId.templateKey)
            .mapTo(Int::class.java)
            .findOne()
            .orElseThrow {
                IllegalStateException("No contract version found for template '${variantId.templateKey}'")
            }

        return handle.createQuery(
            """
            INSERT INTO template_versions (id, tenant_key, catalog_key, template_key, variant_key, template_model, status, contract_version, referenced_paths, created_at, created_by)
            VALUES (:id, :tenantId, :catalogKey, :templateId, :variantId, :templateModel::jsonb, 'draft', :contractVersion, :referencedPaths::jsonb, NOW(), :createdBy)
            RETURNING *
            """,
        )
            .bind("id", versionId)
            .bind("tenantId", variantId.tenantKey)
            .bind("catalogKey", variantId.catalogKey)
            .bind("templateId", variantId.templateKey)
            .bind("variantId", variantId.key)
            .bind("templateModel", templateModelJson)
            .bind("contractVersion", contractVersionId)
            .bind("referencedPaths", referencedPathsJson)
            .bind("createdBy", auditUser)
            .mapTo<TemplateVersion>()
            .one()
    }
}
