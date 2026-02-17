package app.epistola.suite.attributes

import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.commands.DeleteAttributeDefinition
import app.epistola.suite.attributes.commands.UpdateAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.validation.DuplicateIdException
import app.epistola.suite.validation.ValidationException
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class AttributeHandler {

    fun list(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val tenant = GetTenant(tenantId).query() ?: return ServerResponse.notFound().build()
        val attributes = ListAttributeDefinitions(tenantId = tenantId).query()
        return ServerResponse.ok().render(
            "layout/shell",
            mapOf(
                "contentView" to "attributes/list",
                "pageTitle" to "Attributes - Epistola",
                "tenant" to tenant,
                "tenantId" to tenantId.value,
                "attributes" to attributes,
            ),
        )
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        return ServerResponse.ok().render(
            "layout/shell",
            mapOf(
                "contentView" to "attributes/new",
                "pageTitle" to "New Attribute - Epistola",
                "tenantId" to tenantId,
            ),
        )
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val slug = request.params().getFirst("slug")?.trim().orEmpty()
        val displayName = request.params().getFirst("displayName")?.trim().orEmpty()
        val allowedValuesInput = request.params().getFirst("allowedValues")?.trim().orEmpty()
        val allowedValues = parseAllowedValues(allowedValuesInput)

        fun renderFormWithErrors(errors: Map<String, String>): ServerResponse {
            val formData = mapOf("slug" to slug, "displayName" to displayName, "allowedValues" to allowedValuesInput)
            return ServerResponse.ok().render(
                "layout/shell",
                mapOf(
                    "contentView" to "attributes/new",
                    "pageTitle" to "New Attribute - Epistola",
                    "tenantId" to tenantId,
                    "formData" to formData,
                    "errors" to errors,
                ),
            )
        }

        val attributeId = AttributeId.validateOrNull(slug)
        if (attributeId == null) {
            return renderFormWithErrors(
                mapOf(
                    "slug" to "Invalid attribute ID format. Must be 3-50 characters, start with a letter, and contain only lowercase letters, numbers, and hyphens.",
                ),
            )
        }

        try {
            CreateAttributeDefinition(
                id = attributeId,
                tenantId = TenantId.of(tenantId),
                displayName = displayName,
                allowedValues = allowedValues,
            ).execute()
        } catch (e: ValidationException) {
            return renderFormWithErrors(mapOf(e.field to e.message))
        } catch (e: DuplicateIdException) {
            return renderFormWithErrors(mapOf("slug" to "An attribute with this ID already exists"))
        }

        return ServerResponse.status(303)
            .header("Location", "/tenants/$tenantId/attributes")
            .build()
    }

    fun editForm(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val attributeIdStr = request.pathVariable("attributeId")
        val attributeId = AttributeId.validateOrNull(attributeIdStr)
            ?: return ServerResponse.badRequest().build()

        val attribute = GetAttributeDefinition(
            id = attributeId,
            tenantId = TenantId.of(tenantId),
        ).query() ?: return ServerResponse.notFound().build()

        return request.htmx {
            fragment("attributes/list", "edit-attribute-form") {
                "tenantId" to tenantId
                "attribute" to attribute
            }
        }
    }

    fun update(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val attributeIdStr = request.pathVariable("attributeId")
        val attributeId = AttributeId.validateOrNull(attributeIdStr)
            ?: return ServerResponse.badRequest().build()

        val displayName = request.params().getFirst("displayName")?.trim().orEmpty()
        val allowedValuesInput = request.params().getFirst("allowedValues")?.trim().orEmpty()
        val allowedValues = parseAllowedValues(allowedValuesInput)

        try {
            UpdateAttributeDefinition(
                id = attributeId,
                tenantId = TenantId.of(tenantId),
                displayName = displayName,
                allowedValues = allowedValues,
            ).execute() ?: return ServerResponse.notFound().build()
        } catch (e: ValidationException) {
            val attribute = GetAttributeDefinition(
                id = attributeId,
                tenantId = TenantId.of(tenantId),
            ).query() ?: return ServerResponse.notFound().build()
            return request.htmx {
                fragment("attributes/list", "edit-attribute-form") {
                    "tenantId" to tenantId
                    "attribute" to attribute
                    "editError" to e.message
                }
                retarget("#edit-attribute-dialog-body")
                reswap(HxSwap.INNER_HTML)
            }
        }

        val attributes = ListAttributeDefinitions(tenantId = TenantId.of(tenantId)).query()
        return request.htmx {
            fragment("attributes/list", "rows") {
                "tenantId" to tenantId
                "attributes" to attributes
            }
            onNonHtmx { redirect("/tenants/$tenantId/attributes") }
        }
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val attributeIdStr = request.pathVariable("attributeId")
        val attributeId = AttributeId.validateOrNull(attributeIdStr)
            ?: return ServerResponse.badRequest().build()

        DeleteAttributeDefinition(
            id = attributeId,
            tenantId = TenantId.of(tenantId),
        ).execute()

        val attributes = ListAttributeDefinitions(tenantId = TenantId.of(tenantId)).query()
        return request.htmx {
            fragment("attributes/list", "rows") {
                "tenantId" to tenantId
                "attributes" to attributes
            }
            onNonHtmx { redirect("/tenants/$tenantId/attributes") }
        }
    }

    private fun parseAllowedValues(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        return input.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}
