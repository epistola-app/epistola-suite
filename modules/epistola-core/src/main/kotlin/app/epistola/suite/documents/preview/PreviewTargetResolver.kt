// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.preview

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.ContractVersionId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.documents.DefaultVariantNotFoundException
import app.epistola.suite.documents.NoPublishedVersionException
import app.epistola.suite.documents.VersionNotFoundException
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.NoActiveVersionException
import app.epistola.suite.templates.contracts.model.ContractVersion
import app.epistola.suite.templates.contracts.queries.GetContractVersion
import app.epistola.suite.templates.model.TemplateVersion
import app.epistola.suite.templates.queries.activations.GetActiveVersion
import app.epistola.suite.templates.queries.versions.GetLatestPublishedVersion
import app.epistola.suite.templates.queries.versions.GetVersion
import app.epistola.suite.templates.services.VariantResolver
import app.epistola.suite.templates.services.VariantSelectionCriteria
import app.epistola.suite.templates.templateAtAddress
import app.epistola.suite.templates.validation.JsonSchemaValidator
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * The published version a preview renders, the contract it is checked against, and the data to use.
 *
 * @property data The caller's data — or the contract's first example when none was sent — with
 *   schema `default`s filled in, exactly as generation would see it
 */
data class PreviewTarget(
    val variantId: VariantId,
    val version: TemplateVersion,
    val contract: ObjectNode?,
    val data: ObjectNode,
)

/**
 * Resolves what a preview of a *published* version renders: the variant (explicit, by attribute
 * criteria, or the default), the version (explicit, active in an environment, or latest published)
 * and its contract. Shared by [PreviewDocument][app.epistola.suite.documents.queries.PreviewDocument]
 * and [AnalyzePreviewData][app.epistola.suite.documents.queries.AnalyzePreviewData], so that the
 * analysis always describes the same version the preview would render.
 */
@Component
class PreviewTargetResolver(
    private val jdbi: Jdbi,
    private val mediator: Mediator,
    private val variantResolver: VariantResolver,
    private val schemaValidator: JsonSchemaValidator,
) {

    fun resolve(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        templateKey: TemplateKey,
        data: ObjectNode,
        variantKey: VariantKey? = null,
        variantSelectionCriteria: VariantSelectionCriteria? = null,
        versionKey: VersionKey? = null,
        environmentKey: EnvironmentKey? = null,
    ): PreviewTarget {
        val tenantId = TenantId(tenantKey)
        val templateId = TemplateId(templateKey, CatalogId(catalogKey, tenantId))

        val resolvedVariantKey = variantKey
            ?: variantSelectionCriteria?.let { variantResolver.resolve(tenantKey, templateKey, it) }
            ?: resolveDefaultVariant(tenantKey, catalogKey, templateKey)
        val variantId = VariantId(resolvedVariantKey, templateId)

        val version = when {
            versionKey != null -> mediator.query(GetVersion(VersionId(versionKey, variantId)))
                ?: throw VersionNotFoundException(tenantKey, templateKey, resolvedVariantKey, versionKey)
            environmentKey != null -> mediator.query(GetActiveVersion(variantId, EnvironmentId(environmentKey, tenantId)))
                ?: throw NoActiveVersionException(tenantKey, resolvedVariantKey, environmentKey)
            else -> mediator.query(GetLatestPublishedVersion(variantId))
                ?: throw NoPublishedVersionException(tenantKey, templateKey, resolvedVariantKey)
        }

        val contractVersion: ContractVersion? = version.contractVersion?.let { cv ->
            mediator.query(GetContractVersion(id = ContractVersionId(cv, templateId)))
        }

        // No data sent: use the contract's first example.
        val requestedData = if (data.isEmpty) contractVersion?.dataExamples?.firstOrNull()?.data ?: data else data
        val contract = contractVersion?.dataModel
        return PreviewTarget(
            variantId = variantId,
            version = version,
            contract = contract,
            data = if (contract != null) schemaValidator.applyDefaults(contract, requestedData) else requestedData,
        )
    }

    private fun resolveDefaultVariant(tenantKey: TenantKey, catalogKey: CatalogKey, templateKey: TemplateKey): VariantKey {
        val variantId = jdbi.withHandle<String?, Exception> { handle ->
            handle.createQuery(
                """
                SELECT id FROM template_variants
                WHERE tenant_key = :tenantId AND template_resource_id = ${templateAtAddress("tenantId", "catalogKey", "templateId")} AND is_default = TRUE
                """,
            )
                .bind("tenantId", tenantKey)
                .bind("catalogKey", catalogKey)
                .bind("templateId", templateKey)
                .mapTo<String>()
                .findOne()
                .orElse(null)
        }
        variantId ?: throw DefaultVariantNotFoundException(tenantKey, templateKey)
        return VariantKey.of(variantId)
    }
}
