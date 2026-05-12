package app.epistola.suite.templates

import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.handlers.AuthContext
import app.epistola.suite.handlers.buildAttributeDescriptors
import app.epistola.suite.handlers.buildAttributeOptions
import app.epistola.suite.handlers.decorateVariants
import app.epistola.suite.handlers.filterToUsedDescriptors
import app.epistola.suite.handlers.resolveVariantAttributes
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.templateId
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.variantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.DefaultVariantDeletionException
import app.epistola.suite.templates.commands.variants.DeleteVariant
import app.epistola.suite.templates.commands.variants.SetDefaultVariant
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.contracts.queries.GetLatestContractVersion
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.variants.GetVariant
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
import app.epistola.suite.validation.DuplicateIdException
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Handles variant-related routes for document templates.
 * Extracted from DocumentTemplateHandler for better separation of concerns.
 */
@Component
class VariantRouteHandler {

    fun createVariant(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val slug = request.params().getFirst("slug")?.trim()
        val variantKey = if (slug != null) VariantKey.validateOrNull(slug) else null
        if (variantKey == null) {
            return ServerResponse.badRequest().build()
        }

        val title = request.params().getFirst("title")?.trim()?.takeIf { it.isNotEmpty() }
        val description = request.params().getFirst("description")?.trim()?.takeIf { it.isNotEmpty() }
        val attributes = readAttributesFromForm(request, tenantId)

        try {
            CreateVariant(
                id = VariantId(variantKey, templateId),
                title = title,
                description = description,
                attributes = attributes,
            ).execute()
        } catch (e: DuplicateIdException) {
            return renderVariantsSection(request, tenantId, templateId, "A variant with this ID already exists")
        }

        return renderVariantsSection(request, tenantId, templateId)
    }

    fun editVariantForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        val variant = GetVariant(variantId = variantId).query()
            ?: return ServerResponse.notFound().build()

        val attributeDefinitions = ListAttributeDefinitions(tenantId = tenantId).query()
        val attributeDescriptors = buildAttributeDescriptors(attributeDefinitions)
        val attributeOptions = buildAttributeOptions(attributeDefinitions)
        val variantEntries = resolveVariantAttributes(variant.attributes, attributeDescriptors)
        // SpEL can't introspect `contains(String)` on Kotlin's `EmptySet`
        // singleton; wrapping in a parameterized `LinkedHashSet` keeps the
        // Thymeleaf expressions in `edit-variant-form` working when the
        // variant has no attributes set yet.
        val presentQualifiedKeys: Set<String> = LinkedHashSet(variantEntries.mapNotNull { it.descriptor?.qualifiedKey })
        val presentRawKeys: Set<String> = LinkedHashSet(variant.attributes.keys)

        return request.htmx {
            fragment("templates/detail", "edit-variant-form") {
                "tenantId" to tenantId.key.value
                "catalogId" to catalogId.value
                "templateId" to templateId.key
                "variant" to variant
                "attributeDescriptors" to attributeDescriptors
                "attributeDefinitions" to attributeDefinitions
                "attributeOptions" to attributeOptions
                "variantEntries" to variantEntries
                "presentQualifiedKeys" to presentQualifiedKeys
                "presentRawKeys" to presentRawKeys
            }
        }
    }

    fun updateVariant(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        val title = request.params().getFirst("title")?.trim()?.takeIf { it.isNotEmpty() }
        val attributes = readAttributesFromForm(request, tenantId)

        UpdateVariant(
            variantId = variantId,
            title = title,
            attributes = attributes,
        ).execute()

        val variants = GetVariantSummaries(templateId = templateId).query()
        val template = GetDocumentTemplate(id = templateId).query()
            ?: return ServerResponse.notFound().build()
        val attributeDefinitions = ListAttributeDefinitions(tenantId = tenantId).query()
        val attributeDescriptors = buildAttributeDescriptors(attributeDefinitions)
        val attributeOptions = buildAttributeOptions(attributeDefinitions)
        val decoratedVariants = decorateVariants(variants, attributeDescriptors)
        val usedDescriptors = filterToUsedDescriptors(attributeDescriptors, decoratedVariants)
        val editable = app.epistola.suite.catalog.queries.IsCatalogEditable(tenantId.key, catalogId).query()
        val auth = AuthContext.from(SecurityContext.current(), tenantId.key)
        val contractVersion = GetLatestContractVersion(templateId = templateId).query()

        return request.htmx {
            fragment("templates/detail/variants", "variants-section") {
                "tenantId" to tenantId.key.value
                "catalogId" to catalogId.value
                "template" to template
                "variants" to decoratedVariants
                "attributeDescriptors" to attributeDescriptors
                "usedAttributeDescriptors" to usedDescriptors
                "attributeDefinitions" to attributeDefinitions
                "attributeOptions" to attributeOptions
                "editable" to editable
                "auth" to auth
                "contractDataExamples" to contractVersion?.dataExamples
            }
            trigger("closeDialog")
            onNonHtmx { redirect("/tenants/${tenantId.key.value}/templates/$catalogId/${templateId.key}") }
        }
    }

    fun setDefaultVariant(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        SetDefaultVariant(variantId = variantId).execute()

        return renderVariantsSection(request, tenantId, templateId)
    }

    fun deleteVariant(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        try {
            DeleteVariant(variantId = variantId).execute()
        } catch (_: DefaultVariantDeletionException) {
            return renderVariantsSection(request, tenantId, templateId, "Cannot delete the default variant")
        }

        return renderVariantsSection(request, tenantId, templateId)
    }

    private val logger = org.slf4j.LoggerFactory.getLogger(javaClass)

    internal fun renderVariantsSection(
        request: ServerRequest,
        tenantId: TenantId,
        templateId: TemplateId,
        errorMessage: String? = null,
    ): ServerResponse {
        val catalogId = request.catalogId()
        val variants = GetVariantSummaries(templateId = templateId).query()
        val template = GetDocumentTemplate(id = templateId).query()
            ?: return ServerResponse.notFound().build()
        val attributeDefinitions = ListAttributeDefinitions(tenantId = tenantId).query()
        val attributeDescriptors = buildAttributeDescriptors(attributeDefinitions)
        val attributeOptions = buildAttributeOptions(attributeDefinitions)
        val decoratedVariants = decorateVariants(variants, attributeDescriptors)
        val usedDescriptors = filterToUsedDescriptors(attributeDescriptors, decoratedVariants)
        val editable = app.epistola.suite.catalog.queries.IsCatalogEditable(tenantId.key, catalogId).query()
        logger.info("renderVariantsSection: variants={}, editable={}, catalogId={}, isHtmx={}", variants.size, editable, catalogId, request.isHtmx)
        val auth = AuthContext.from(SecurityContext.current(), tenantId.key)
        val contractVersion = GetLatestContractVersion(templateId = templateId).query()

        return request.htmx {
            fragment("templates/detail/variants", "variants-section") {
                "tenantId" to tenantId.key.value
                "catalogId" to catalogId.value
                "template" to template
                "variants" to decoratedVariants
                "attributeDescriptors" to attributeDescriptors
                "usedAttributeDescriptors" to usedDescriptors
                "attributeDefinitions" to attributeDefinitions
                "attributeOptions" to attributeOptions
                "editable" to editable
                "auth" to auth
                "contractDataExamples" to contractVersion?.dataExamples
                if (errorMessage != null) {
                    "error" to errorMessage
                }
            }
            trigger("closeDialog")
            onNonHtmx { redirect("/tenants/${tenantId.key.value}/templates/$catalogId/${templateId.key}") }
        }
    }

    /**
     * Reads variant attributes from form parameters. Inputs are named
     * `attr_<qualifiedKey>` (`attr_system.locale`) on the new forms; older
     * forms still submit `attr_<bareSlug>` (`attr_language`). Both shapes
     * are accepted so cached pages from before the qualified-key rollout
     * keep working. Empty values are excluded.
     *
     * Stored keys mirror the input form: qualified inputs land as qualified
     * keys on the variant; bare-slug inputs land as bare slugs. The
     * validator + UI both accept either form (see `validateAttributes` and
     * `resolveAttributeKey`).
     */
    private fun readAttributesFromForm(request: ServerRequest, tenantId: TenantId): Map<String, String> {
        val descriptors = buildAttributeDescriptors(ListAttributeDefinitions(tenantId).query())
        val attributes = mutableMapOf<String, String>()
        for (descriptor in descriptors) {
            val qualifiedValue = request.params().getFirst("attr_${descriptor.qualifiedKey}")?.trim()
            if (!qualifiedValue.isNullOrEmpty()) {
                attributes[descriptor.qualifiedKey] = qualifiedValue
                continue
            }
            val bareValue = request.params().getFirst("attr_${descriptor.bareSlug}")?.trim()
            if (!bareValue.isNullOrEmpty()) {
                attributes[descriptor.bareSlug] = bareValue
            }
        }
        return attributes
    }
}
