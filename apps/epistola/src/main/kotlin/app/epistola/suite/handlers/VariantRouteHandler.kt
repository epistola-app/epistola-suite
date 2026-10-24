// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.catalog.queries.IsCatalogEditable
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.common.ResourceModifiedException
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.handlers.AuthContext
import app.epistola.suite.handlers.buildAttributeDescriptors
import app.epistola.suite.handlers.buildAttributeOptions
import app.epistola.suite.handlers.decorateVariants
import app.epistola.suite.handlers.filterToUsedDescriptors
import app.epistola.suite.handlers.resolveVariantAttributes
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.catalogSegment
import app.epistola.suite.htmx.catalogVersion
import app.epistola.suite.htmx.expectedRevision
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.templateId
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.variantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.templates.commands.MarkTemplateReady
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.DefaultVariantDeletionException
import app.epistola.suite.templates.commands.variants.DeleteVariant
import app.epistola.suite.templates.commands.variants.MarkVariantReady
import app.epistola.suite.templates.commands.variants.SetDefaultVariant
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.contracts.queries.GetDataContract
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.variants.GetVariant
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
import app.epistola.suite.validation.DuplicateIdException
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.ValidationException
import app.epistola.suite.validation.formMessage
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
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val form = request.form {
            field("slug") {
                required()
                asVariantId()
            }
            field("title") {
                required()
                maxLength(MAX_NAME_LENGTH)
            }
        }
        if (form.hasErrors()) {
            return variantFormError(request, tenantId, templateId, "create-variant-error", form.errors.values.first())
        }

        val description = request.params().getFirst("description")?.trim()?.takeIf { it.isNotEmpty() }
        val attributes = readAttributesFromForm(request, tenantId)

        try {
            CreateVariant(
                id = VariantId(form.getVariantId("slug")!!, templateId),
                title = form["title"],
                description = description,
                attributes = attributes,
                catalogVersion = request.catalogVersion(),
            ).execute()
        } catch (e: DuplicateIdException) {
            return variantFormError(request, tenantId, templateId, "create-variant-error", e.formMessage)
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

        val version = request.catalogVersion()
        val variant = GetVariant(variantId = variantId, catalogVersion = version).query()
            ?: return ServerResponse.notFound().build()

        val attributeDefinitions = ListAttributeDefinitions(tenantId = tenantId, catalogVersion = version).query()
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
                "catalogSegment" to request.catalogSegment().value
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

        val form = request.form {
            field("title") {
                required()
                maxLength(MAX_NAME_LENGTH)
            }
        }
        if (form.hasErrors()) {
            return variantFormError(request, tenantId, templateId, "edit-variant-error", form.errors.values.first())
        }

        try {
            UpdateVariant(
                variantId = variantId,
                title = form["title"],
                attributes = readAttributesFromForm(request, tenantId),
                catalogVersion = request.catalogVersion(),
                expectedRevision = request.expectedRevision(),
            ).execute()
        } catch (_: ResourceModifiedException) {
            // What was typed stays in the dialog; reopening it shows the other change to apply it to.
            return variantFormError(request, tenantId, templateId, "edit-variant-error", "Someone else changed this variant since you opened it. Close this and open it again to see their change, then make yours.")
        }

        return renderVariantsSection(request, tenantId, templateId)
    }

    fun setDefaultVariant(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        SetDefaultVariant(variantId = variantId, catalogVersion = request.catalogVersion()).execute()

        return renderVariantsSection(request, tenantId, templateId)
    }

    fun deleteVariant(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        try {
            DeleteVariant(variantId = variantId, catalogVersion = request.catalogVersion()).execute()
        } catch (_: DefaultVariantDeletionException) {
            return renderVariantsSection(request, tenantId, templateId, "Cannot delete the default variant")
        }

        return renderVariantsSection(request, tenantId, templateId)
    }

    /**
     * OOB-swaps an error into the dialog's form-error slot with no primary swap, so the
     * dialog stays open with the user's input instead of the list swapping and closing.
     */
    private fun variantFormError(
        request: ServerRequest,
        tenantId: TenantId,
        templateId: TemplateId,
        errorId: String,
        message: String,
    ): ServerResponse {
        val catalogId = request.catalogSegment().value
        return request.htmx {
            globalFormError(errorId, message)
            onFullPage { redirect("/tenants/${tenantId.key.value}/catalogs/$catalogId/templates/${templateId.key}") }
        }
    }

    /**
     * Marks one variant's working model ready. A refusal (the model is not publishable, or a
     * stencil instance in it does not fit) re-renders the variants with the reason.
     */
    fun markVariantReady(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        val error = try {
            MarkVariantReady(variantId = variantId, catalogVersion = request.catalogVersion()).execute()
            null
        } catch (e: ValidationException) {
            e.message
        }
        return renderVariantsSection(request, tenantId, templateId, error)
    }

    /** Marks every variant of the template ready; a refusal is shown like [markVariantReady]'s. */
    fun markTemplateReady(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val error = try {
            MarkTemplateReady(templateId = templateId, catalogVersion = request.catalogVersion()).execute()
            null
        } catch (e: ValidationException) {
            e.message
        }
        return renderVariantsSection(request, tenantId, templateId, error)
    }

    internal fun renderVariantsSection(
        request: ServerRequest,
        tenantId: TenantId,
        templateId: TemplateId,
        errorMessage: String? = null,
    ): ServerResponse {
        val catalogId = request.catalogId()
        val segment = request.catalogSegment()
        val version = segment.ref(tenantId.key)
        val template = GetDocumentTemplate(id = templateId, catalogVersion = version).query()
            ?: return ServerResponse.notFound().build()
        val editable = IsCatalogEditable(segment.catalogIn(tenantId)).query()
        val auth = AuthContext.from(SecurityContext.current(), tenantId.key)
        val variants = variantsModel(templateId, editable, auth, version)

        return request.htmx {
            fragment("templates/detail/variants", "variants-section") {
                "tenantId" to tenantId.key.value
                "catalogId" to catalogId.value
                "catalogSegment" to segment.value
                "template" to template
                putAll(variants)
                "editable" to editable
                "auth" to auth
                if (errorMessage != null) {
                    "error" to errorMessage
                }
            }
            trigger("closeDialog")
            onFullPage { redirect("/tenants/${tenantId.key.value}/catalogs/${segment.value}/templates/${templateId.key}") }
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
        val descriptors = buildAttributeDescriptors(ListAttributeDefinitions(tenantId, catalogVersion = request.catalogVersion()).query())
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

/**
 * The model the variants tab renders: the variants with their attributes and readiness, the
 * attribute pickers, and the data examples the preview uses.
 *
 * `offerMarkAllReady` is the one name the template gates "Mark all variants ready" on: the viewer
 * may mark (`TEMPLATE_EDIT`), the catalog is editable, and a variant is not ready yet.
 */
internal fun variantsModel(templateId: TemplateId, editable: Boolean, auth: AuthContext, version: CatalogReleaseRef? = null): Map<String, Any?> {
    val variants = GetVariantSummaries(templateId = templateId, catalogVersion = version).query()
    val attributeDefinitions = ListAttributeDefinitions(tenantId = templateId.tenantId, catalogVersion = version).query()
    val attributeDescriptors = buildAttributeDescriptors(attributeDefinitions)
    val markReadyAllowed = editable && auth.has(Permission.TEMPLATE_EDIT)
    val decoratedVariants = decorateVariants(variants, attributeDescriptors, markReadyAllowed)
    return mapOf(
        "variants" to decoratedVariants,
        "offerMarkAllReady" to (markReadyAllowed && variants.any { !it.ready }),
        "attributeDescriptors" to attributeDescriptors,
        "usedAttributeDescriptors" to filterToUsedDescriptors(attributeDescriptors, decoratedVariants),
        "attributeDefinitions" to attributeDefinitions,
        "attributeOptions" to buildAttributeOptions(attributeDefinitions),
        "contractDataExamples" to GetDataContract(templateId = templateId, catalogVersion = version).query()?.dataExamples,
    )
}
