// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.api.v1.shared.UnsupportedSortDirectionException
import app.epistola.suite.api.v1.shared.UnsupportedSortException
import app.epistola.suite.assets.AssetInUseException
import app.epistola.suite.assets.AssetNotFoundException
import app.epistola.suite.assets.AssetTooLargeException
import app.epistola.suite.assets.UnsupportedAssetTypeException
import app.epistola.suite.attributes.AttributeNotFoundException
import app.epistola.suite.attributes.codelists.CodeListNotFoundException
import app.epistola.suite.attributes.codelists.commands.CodeListInUseException
import app.epistola.suite.attributes.codelists.commands.CodeListNotRefreshableException
import app.epistola.suite.attributes.commands.AllowedValuesInUseException
import app.epistola.suite.attributes.commands.AttributeInUseException
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogNotUpgradeableException
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.migrations.CatalogSchemaTooNewException
import app.epistola.suite.catalog.migrations.CatalogSchemaTooOldException
import app.epistola.suite.catalog.migrations.CatalogSchemaUnknownException
import app.epistola.suite.documents.DefaultVariantNotFoundException
import app.epistola.suite.documents.DocumentNotFoundException
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.documents.GenerationJobNotCancellableException
import app.epistola.suite.documents.GenerationJobNotFoundException
import app.epistola.suite.documents.NoPublishedVersionException
import app.epistola.suite.documents.TemplateVariantNotFoundException
import app.epistola.suite.documents.VersionNotFoundException
import app.epistola.suite.environments.EnvironmentInUseException
import app.epistola.suite.fonts.FontNotFoundException
import app.epistola.suite.stencils.StencilNotFoundException
import app.epistola.suite.stencils.StencilVersionNotDraftException
import app.epistola.suite.stencils.StencilVersionNotFoundException
import app.epistola.suite.stencils.StencilVersionNotPublishedException
import app.epistola.suite.templates.ActivationNotFoundException
import app.epistola.suite.templates.DraftNotFoundException
import app.epistola.suite.templates.NoActiveVersionException
import app.epistola.suite.templates.TemplateNotFoundException
import app.epistola.suite.templates.VersionArchivedException
import app.epistola.suite.templates.VersionNotDraftException
import app.epistola.suite.templates.VersionNotPublishedException
import app.epistola.suite.templates.commands.variants.DefaultVariantDeletionException
import app.epistola.suite.templates.commands.versions.VersionStillActiveException
import app.epistola.suite.templates.contracts.ContractPublishConflictException
import app.epistola.suite.templates.services.AmbiguousVariantResolutionException
import app.epistola.suite.templates.services.NoMatchingVariantException
import app.epistola.suite.tenants.TenantNotFoundException
import app.epistola.suite.themes.ThemeInUseException
import app.epistola.suite.themes.ThemeNotFoundException

/**
 * Immutable mapping data for a single exception type.
 *
 * @property problemType The RFC 9457 problem type (code, title, status, …).
 * @property detail      Produces the human-readable detail string from the exception.
 * @property extensions Produces the extension map rendered as top-level JSON fields.
 * @property logMessage  Produces the `logger.warn` payload for the exception.
 */
data class ApiExceptionMapping(
    val problemType: ApiProblemType,
    val detail: (Throwable) -> String,
    val extensions: (Throwable) -> Map<String, Any?>,
    val logMessage: (Throwable) -> String,
)

/**
 * Central registry that maps concrete exception classes to their problem-detail
 * metadata. Used by [ApiExceptionHandler.handleMappedApiException] so that
 * simple domain exceptions do not need a dedicated `@ExceptionHandler` method.
 */
object ApiExceptionMappings {
    private val registry: Map<Class<out Throwable>, ApiExceptionMapping>

    init {
        val builder = ApiExceptionMappingBuilder()

        builder.register<UnsupportedSortException>(
            problemType = ApiProblemTypes.UNSUPPORTED_SORT,
            defaultDetail = "The requested sort key is not supported by this endpoint",
            extensions = { mapOf("value" to it.value, "supportedValues" to it.supportedValues) },
            logMessage = { "Unsupported sort '${it.value}'; supported: ${it.supportedValues.joinToString(", ")}" },
        )

        builder.register<UnsupportedSortDirectionException>(
            problemType = ApiProblemTypes.UNSUPPORTED_SORT_DIRECTION,
            defaultDetail = "The requested sort direction is not supported",
            extensions = { mapOf("value" to it.value, "supportedValues" to it.supportedValues) },
            logMessage = { "Unsupported sort direction '${it.value}'; supported: ${it.supportedValues.joinToString(", ")}" },
        )

        builder.register<ThemeNotFoundException>(
            problemType = ApiProblemTypes.THEME_NOT_FOUND,
            defaultDetail = "Theme not found",
            extensions = { mapOf("themeId" to it.themeId.value) },
            logMessage = { "Theme not found: ${it.themeId}" },
        )

        builder.register<TenantNotFoundException>(
            problemType = ApiProblemTypes.TENANT_NOT_FOUND,
            defaultDetail = "Tenant not found",
            extensions = { mapOf("tenantId" to it.tenantId.value) },
            logMessage = { "Tenant not found: ${it.tenantId}" },
        )

        builder.register<AttributeNotFoundException>(
            problemType = ApiProblemTypes.ATTRIBUTE_NOT_FOUND,
            defaultDetail = "Attribute not found",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "catalogId" to it.catalogId.value,
                    "attributeId" to it.attributeId.value,
                )
            },
            logMessage = { "Attribute not found: tenant=${it.tenantId} catalog=${it.catalogId} attribute=${it.attributeId}" },
        )

        builder.register<CodeListNotFoundException>(
            problemType = ApiProblemTypes.CODE_LIST_NOT_FOUND,
            defaultDetail = "Code list not found",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "catalogId" to it.catalogId.value,
                    "codeListId" to it.codeListId.value,
                )
            },
            logMessage = { "Code list not found: tenant=${it.tenantId} catalog=${it.catalogId} codeList=${it.codeListId}" },
        )

        builder.register<TemplateNotFoundException>(
            problemType = ApiProblemTypes.TEMPLATE_NOT_FOUND,
            defaultDetail = "Template not found",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "templateId" to it.templateId.value,
                )
            },
            logMessage = { "Template not found: tenant=${it.tenantId} template=${it.templateId}" },
        )

        builder.register<StencilNotFoundException>(
            problemType = ApiProblemTypes.STENCIL_NOT_FOUND,
            defaultDetail = "Stencil not found",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "stencilId" to it.stencilId.value,
                )
            },
            logMessage = { "Stencil not found: tenant=${it.tenantId} stencil=${it.stencilId}" },
        )

        builder.register<StencilVersionNotFoundException>(
            problemType = ApiProblemTypes.STENCIL_VERSION_NOT_FOUND,
            defaultDetail = "Stencil version not found",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "catalogId" to it.catalogId.value,
                    "stencilId" to it.stencilId.value,
                    "versionId" to it.versionId.value,
                )
            },
            logMessage = { "Stencil version not found: tenant=${it.tenantId} catalog=${it.catalogId} stencil=${it.stencilId} version=${it.versionId}" },
        )

        builder.register<StencilVersionNotDraftException>(
            problemType = ApiProblemTypes.STENCIL_VERSION_NOT_DRAFT,
            defaultDetail = "Stencil version is not a draft",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "catalogId" to it.catalogId.value,
                    "stencilId" to it.stencilId.value,
                    "versionId" to it.versionId.value,
                )
            },
            logMessage = { "Stencil version not draft: tenant=${it.tenantId} catalog=${it.catalogId} stencil=${it.stencilId} version=${it.versionId}" },
        )

        builder.register<StencilVersionNotPublishedException>(
            problemType = ApiProblemTypes.STENCIL_VERSION_NOT_PUBLISHED,
            defaultDetail = "Stencil version is not published",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "catalogId" to it.catalogId.value,
                    "stencilId" to it.stencilId.value,
                    "versionId" to it.versionId.value,
                )
            },
            logMessage = { "Stencil version not published: tenant=${it.tenantId} catalog=${it.catalogId} stencil=${it.stencilId} version=${it.versionId}" },
        )

        builder.register<FontNotFoundException>(
            problemType = ApiProblemTypes.FONT_NOT_FOUND,
            defaultDetail = "Font not found",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "catalogId" to it.catalogId.value,
                    "fontId" to it.fontId.value,
                )
            },
            logMessage = { "Font not found: tenant=${it.tenantId} catalog=${it.catalogId} font=${it.fontId}" },
        )

        builder.register<GenerationJobNotFoundException>(
            problemType = ApiProblemTypes.GENERATION_JOB_NOT_FOUND,
            defaultDetail = "Generation job not found",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "requestId" to it.requestId.value.toString(),
                )
            },
            logMessage = { "Generation job not found: tenant=${it.tenantId} requestId=${it.requestId}" },
        )

        builder.register<GenerationJobNotCancellableException>(
            problemType = ApiProblemTypes.GENERATION_JOB_NOT_CANCELLABLE,
            defaultDetail = "Generation job cannot be cancelled",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "requestId" to it.requestId.value.toString(),
                )
            },
            logMessage = { "Generation job not cancellable: tenant=${it.tenantId} requestId=${it.requestId} reason=${it.reason}" },
        )

        builder.register<DocumentNotFoundException>(
            problemType = ApiProblemTypes.DOCUMENT_NOT_FOUND,
            defaultDetail = "Document not found",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "documentId" to it.documentId.value.toString(),
                )
            },
            logMessage = { "Document not found: tenant=${it.tenantId} documentId=${it.documentId}" },
        )

        builder.register<DraftNotFoundException>(
            problemType = ApiProblemTypes.DRAFT_NOT_FOUND,
            defaultDetail = "Draft not found",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "variantId" to it.variantId.value,
                )
            },
            logMessage = { "Draft not found: tenant=${it.tenantId} variant=${it.variantId}" },
        )

        builder.register<VersionNotDraftException>(
            problemType = ApiProblemTypes.VERSION_NOT_DRAFT,
            defaultDetail = "Version is not a draft",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "versionId" to it.versionId.value,
                )
            },
            logMessage = { "Version not draft: tenant=${it.tenantId} version=${it.versionId}" },
        )

        builder.register<VersionNotPublishedException>(
            problemType = ApiProblemTypes.VERSION_NOT_PUBLISHED,
            defaultDetail = "Version is not published",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "versionId" to it.versionId.value,
                )
            },
            logMessage = { "Version not published: tenant=${it.tenantId} version=${it.versionId}" },
        )

        builder.register<VersionArchivedException>(
            problemType = ApiProblemTypes.VERSION_ARCHIVED,
            defaultDetail = "Version is archived",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "versionId" to it.versionId.value,
                )
            },
            logMessage = { "Version archived: tenant=${it.tenantId} version=${it.versionId}" },
        )

        builder.register<ActivationNotFoundException>(
            problemType = ApiProblemTypes.ACTIVATION_NOT_FOUND,
            defaultDetail = "Activation not found",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "variantId" to it.variantId.value,
                    "environmentId" to it.environmentId.value,
                )
            },
            logMessage = { "Activation not found: tenant=${it.tenantId} variant=${it.variantId} environment=${it.environmentId}" },
        )

        builder.register<NoActiveVersionException>(
            problemType = ApiProblemTypes.NO_ACTIVE_VERSION,
            defaultDetail = "No active version found",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "variantId" to it.variantId.value,
                    "environmentId" to it.environmentId.value,
                )
            },
            logMessage = { "No active version: tenant=${it.tenantId} variant=${it.variantId} environment=${it.environmentId}" },
        )

        builder.register<ThemeInUseException>(
            problemType = ApiProblemTypes.THEME_IN_USE,
            defaultDetail = "Theme is in use and cannot be deleted",
            extensions = { mapOf("themeId" to it.themeId.value) },
            logMessage = { "Theme in use, cannot delete: ${it.themeId}" },
        )

        builder.register<DefaultVariantDeletionException>(
            problemType = ApiProblemTypes.DEFAULT_VARIANT_DELETION,
            defaultDetail = "Cannot delete the default variant",
            extensions = { mapOf("variantId" to it.variantId.value) },
            logMessage = { "Cannot delete default variant: ${it.variantId}" },
        )

        builder.register<VersionStillActiveException>(
            problemType = ApiProblemTypes.VERSION_STILL_ACTIVE,
            defaultDetail = "Version is still active in one or more environments",
            extensions = {
                mapOf(
                    "versionId" to it.versionId.value,
                    "variantId" to it.variantId.value,
                    "activeEnvironments" to it.activeEnvironments.map { env -> env.value },
                )
            },
            logMessage = { "Cannot archive version ${it.versionId}: still active in environments" },
        )

        builder.register<NoMatchingVariantException>(
            problemType = ApiProblemTypes.NO_MATCHING_VARIANT,
            defaultDetail = "No matching variant found",
            extensions = {
                mapOf(
                    "templateId" to it.templateId.value,
                    "requiredAttributes" to it.criteria.requiredAttributes,
                )
            },
            logMessage = { "No matching variant for template ${it.templateId}: ${it.criteria}" },
        )

        builder.register<AmbiguousVariantResolutionException>(
            problemType = ApiProblemTypes.AMBIGUOUS_VARIANT,
            defaultDetail = "Ambiguous variant resolution",
            extensions = {
                mapOf(
                    "templateId" to it.templateId.value,
                    "tiedVariants" to it.tiedVariantIds.map { id -> id.value },
                )
            },
            logMessage = { "Ambiguous variant resolution for template ${it.templateId}: ${it.tiedVariantIds}" },
        )

        builder.register<AttributeInUseException>(
            problemType = ApiProblemTypes.ATTRIBUTE_IN_USE,
            defaultDetail = "Attribute is in use and cannot be deleted",
            extensions = {
                mapOf(
                    "attributeId" to it.attributeId.key,
                    "variantCount" to it.variantCount,
                )
            },
            logMessage = { "Cannot delete attribute ${it.attributeId}: still in use by ${it.variantCount} variant(s)" },
        )

        builder.register<AllowedValuesInUseException>(
            problemType = ApiProblemTypes.ALLOWED_VALUES_IN_USE,
            defaultDetail = "Cannot remove allowed values that are in use by existing variants",
            extensions = {
                mapOf(
                    "attributeId" to it.attributeId.key,
                    "valuesInUse" to it.removedValues,
                )
            },
            logMessage = { "Cannot narrow allowed values for attribute ${it.attributeId}: values ${it.removedValues} are in use" },
        )

        builder.register<TemplateVariantNotFoundException>(
            problemType = ApiProblemTypes.TEMPLATE_VARIANT_NOT_FOUND,
            defaultDetail = "Template variant not found",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "templateId" to it.templateId.value,
                    "variantId" to it.variantId.value,
                )
            },
            logMessage = { "Template variant not found: ${it.message}" },
        )

        builder.register<VersionNotFoundException>(
            problemType = ApiProblemTypes.VERSION_NOT_FOUND,
            defaultDetail = "Version not found",
            extensions = {
                mapOf(
                    "templateId" to it.templateId.value,
                    "variantId" to it.variantId.value,
                    "versionId" to it.versionId.value,
                )
            },
            logMessage = { "Version not found: ${it.message}" },
        )

        builder.register<EnvironmentNotFoundException>(
            problemType = ApiProblemTypes.ENVIRONMENT_NOT_FOUND,
            defaultDetail = "Environment not found",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "environmentId" to it.environmentId.value,
                )
            },
            logMessage = { "Environment not found: ${it.message}" },
        )

        builder.register<NoPublishedVersionException>(
            problemType = ApiProblemTypes.NO_PUBLISHED_VERSION,
            defaultDetail = "No published version found",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "templateId" to it.templateId.value,
                    "variantId" to it.variantId.value,
                )
            },
            logMessage = { "No published version: ${it.message}" },
        )

        builder.register<DefaultVariantNotFoundException>(
            problemType = ApiProblemTypes.DEFAULT_VARIANT_NOT_FOUND,
            defaultDetail = "No default variant found",
            extensions = {
                mapOf(
                    "tenantId" to it.tenantId.value,
                    "templateId" to it.templateId.value,
                )
            },
            logMessage = { "Default variant not found: ${it.message}" },
        )

        builder.register<AssetNotFoundException>(
            problemType = ApiProblemTypes.ASSET_NOT_FOUND,
            defaultDetail = "Asset not found",
            extensions = { emptyMap() },
            logMessage = { "Asset not found: ${it.message}" },
        )

        builder.register<AssetTooLargeException>(
            problemType = ApiProblemTypes.ASSET_TOO_LARGE,
            defaultDetail = "Asset exceeds maximum size",
            extensions = { emptyMap() },
            logMessage = { "Asset too large: ${it.message}" },
        )

        builder.register<UnsupportedAssetTypeException>(
            problemType = ApiProblemTypes.UNSUPPORTED_ASSET_TYPE,
            defaultDetail = "Unsupported asset media type",
            extensions = { emptyMap() },
            logMessage = { "Unsupported asset type: ${it.message}" },
        )

        builder.register<AssetInUseException>(
            problemType = ApiProblemTypes.ASSET_IN_USE,
            defaultDetail = "Asset is in use and cannot be deleted",
            extensions = {
                mapOf(
                    "assetId" to it.assetId.value,
                    "usages" to it.usages.map { usage ->
                        mapOf(
                            "templateName" to usage.templateName,
                            "variantTitle" to usage.variantTitle,
                        )
                    },
                )
            },
            logMessage = { "Cannot delete asset ${it.assetId}: in use by ${it.usages.size} template(s)" },
        )

        builder.register<EnvironmentInUseException>(
            problemType = ApiProblemTypes.ENVIRONMENT_IN_USE,
            defaultDetail = "Environment is in use and cannot be deleted",
            extensions = {
                mapOf(
                    "environmentId" to it.environmentId.value,
                    "activationCount" to it.activationCount,
                )
            },
            logMessage = { "Cannot delete environment ${it.environmentId}: ${it.activationCount} active activation(s)" },
        )

        builder.register<CatalogReadOnlyException>(
            problemType = ApiProblemTypes.CATALOG_READ_ONLY,
            defaultDetail = "Catalog is subscribed and cannot be modified through this API",
            extensions = { emptyMap() },
            logMessage = { "Write to read-only catalog rejected: ${it.message}" },
        )

        builder.register<CatalogNotFoundException>(
            problemType = ApiProblemTypes.CATALOG_NOT_FOUND,
            defaultDetail = "Catalog not found",
            extensions = { mapOf("catalogId" to it.catalogKey.value) },
            logMessage = { "Catalog not found: ${it.catalogKey}" },
        )

        builder.register<CatalogNotUpgradeableException>(
            problemType = ApiProblemTypes.CATALOG_NOT_UPGRADEABLE,
            defaultDetail = "Catalog cannot be upgraded",
            extensions = { mapOf("catalogId" to it.catalogKey.value) },
            logMessage = { "Catalog not upgradeable: ${it.message}" },
        )

        builder.register<CatalogSchemaTooNewException>(
            problemType = ApiProblemTypes.CATALOG_SCHEMA_TOO_NEW,
            defaultDetail = "Catalog wire schema version is newer than this instance supports",
            extensions = { mapOf("version" to it.version, "supportedVersion" to it.current) },
            logMessage = { "Catalog import rejected — wire schema too new: ${it.message}" },
        )

        builder.register<CatalogSchemaTooOldException>(
            problemType = ApiProblemTypes.CATALOG_SCHEMA_TOO_OLD,
            defaultDetail = "Catalog wire schema version predates the oldest supported version",
            extensions = { mapOf("version" to it.version, "baselineVersion" to it.baseline) },
            logMessage = { "Catalog import rejected — wire schema too old: ${it.message}" },
        )

        builder.register<CatalogSchemaUnknownException>(
            problemType = ApiProblemTypes.CATALOG_SCHEMA_UNKNOWN,
            defaultDetail = "Unrecognised catalog wire payload",
            logMessage = { "Catalog import rejected — unrecognised wire payload: ${it.message}" },
        )

        builder.register<CodeListInUseException>(
            problemType = ApiProblemTypes.CODE_LIST_IN_USE,
            defaultDetail = "Code list is in use and cannot be deleted",
            extensions = { emptyMap() },
            logMessage = { "Cannot delete code list: still bound by an attribute. ${it.message}" },
        )

        builder.register<CodeListNotRefreshableException>(
            problemType = ApiProblemTypes.CODE_LIST_NOT_REFRESHABLE,
            defaultDetail = "Code list is not URL-sourced and cannot be refreshed",
            extensions = { emptyMap() },
            logMessage = { "Code list refresh rejected: ${it.message}" },
        )

        builder.register<ApiOperationNotImplementedException>(
            problemType = ApiProblemTypes.OPERATION_NOT_IMPLEMENTED,
            defaultDetail = "API operation is not implemented",
            extensions = { mapOf("operation" to it.operation) },
            logMessage = { "API operation not implemented: ${it.operation}" },
        )

        builder.register<ContractPublishConflictException>(
            problemType = ApiProblemTypes.CONTRACT_PUBLISH_CONFLICT,
            defaultDetail = "Schema change is backwards-incompatible; retry with forceUpdate=true to confirm",
            extensions = { mapOf("breakingChanges" to it.breakingChanges) },
            logMessage = { "Contract publish rejected as breaking: ${it.breakingChanges}" },
        )

        registry = builder.build()
    }

    /**
     * Looks up the mapping for a concrete exception class.
     * Returns `null` when the exception has not been registered (callers should
     * fall back to their own specialised handling).
     */
    fun forException(ex: Throwable): ApiExceptionMapping? = registry[ex.javaClass]

    /**
     * Returns every exception class that has been registered in this registry.
     * Used by consistency tests to verify parity with the
     * [ApiExceptionHandler.handleMappedApiException] annotation.
     */
    fun registeredClasses(): Set<Class<out Throwable>> = registry.keys.toSet()
}

private class ApiExceptionMappingBuilder {
    private val map = mutableMapOf<Class<out Throwable>, ApiExceptionMapping>()

    inline fun <reified T : Throwable> register(
        problemType: ApiProblemType,
        defaultDetail: String,
        crossinline extensions: (T) -> Map<String, Any?> = { emptyMap() },
        crossinline logMessage: (T) -> String = { "" },
    ) {
        map[T::class.java] = ApiExceptionMapping(
            problemType = problemType,
            detail = { ex -> (ex as T).message ?: defaultDetail },
            extensions = { ex -> extensions(ex as T) },
            logMessage = { ex -> logMessage(ex as T) },
        )
    }

    fun build(): Map<Class<out Throwable>, ApiExceptionMapping> = map.toMap()
}
