package app.epistola.suite.attributes

import app.epistola.suite.attributes.codelists.queries.ListCodeLists
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.commands.DeleteAttributeDefinition
import app.epistola.suite.attributes.commands.UpdateAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.attributeId
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class AttributeHandler {

    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogFilter = request.param("catalog").orElse(null)?.ifBlank { null }?.let { CatalogKey.of(it) }
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()
        val catalogs = ListCatalogs(tenantId.key).query()
        val attributes = ListAttributeDefinitions(tenantId = tenantId, catalogKey = catalogFilter).query()
        return ServerResponse.ok().page("attributes/list") {
            "pageTitle" to "Attributes - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "catalogs" to catalogs
            "selectedCatalog" to (catalogFilter?.value ?: "")
            "attributes" to attributes
        }
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        return request.htmx {
            // In-app trigger (hx-get → #dialog-mount): just the dialog fragment.
            fragment("attributes/new", "dialog") {
                "tenantId" to tenantId.key
                "authoredCatalogs" to authoredCatalogs(tenantId)
                "codeLists" to ListCodeLists(tenantId).query()
            }
            // Direct navigation / boost: the host list page with the dialog
            // embedded in its mount (openDialog=true), opened on load by the JS.
            onNonHtmx {
                page("attributes/list") {
                    attributePageModel(tenantId)
                    "openDialog" to true
                    "authoredCatalogs" to authoredCatalogs(tenantId)
                    "codeLists" to ListCodeLists(tenantId).query()
                }
            }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)

        val form = request.form {
            field("catalog") {}
            field("constraintKind") {}
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(50)
                // Folds the old "invalid AttributeKey" branch into field validation
                // (same "Invalid attribute ID format" error) so all three failure
                // modes share one error path — mirroring DocumentTemplateHandler.
                asAttributeId()
            }
            field("displayName") {
                required()
                maxLength(100)
            }
            field("allowedValues") {}
            field("codeList") {}
        }

        val catalogKey = CatalogKey.of(form.formData["catalog"]?.ifBlank { null } ?: return ServerResponse.badRequest().build())

        // Constraint parsing is preserved verbatim: constraintKind defaults to
        // "free"; the inline/code-list panes feed parseConstraint → the command.
        val constraintKind = form.formData["constraintKind"]?.ifBlank { null } ?: "free"
        val allowedValuesInput = request.params().getFirst("allowedValues")?.trim().orEmpty()
        val codeListSelection = request.params().getFirst("codeList")?.ifBlank { null }
        val (allowedValues, codeListId) = parseConstraint(constraintKind, allowedValuesInput, codeListSelection, tenantId)

        // Field validation (incl. slug/AttributeKey) and the command-level failure
        // (duplicate slug) both land as `errors` on the FormData, so they share one
        // error path — mirroring EnvironmentHandler.create.
        val result = if (form.hasErrors()) {
            form
        } else {
            form.executeOrFormError {
                CreateAttributeDefinition(
                    // Safe !!: asAttributeId already rejected an invalid non-blank
                    // slug, and required() rejected a blank one, so success is reached
                    // only with a valid key.
                    id = AttributeId(AttributeKey.validateOrNull(form["slug"])!!, CatalogId(catalogKey, tenantId)),
                    displayName = form["displayName"],
                    allowedValues = allowedValues,
                    codeListId = codeListId,
                ).execute()
            }
        }

        if (result.hasErrors()) {
            return request.htmx {
                // Re-render the form inside the dialog (retargeted to the form, not
                // the list) with inline errors + preserved values. `tenantId`,
                // `authoredCatalogs`, and `codeLists` are the prefill the form
                // fragment needs to rebuild its action URL and the two <select>s.
                dialogFieldErrors(
                    template = "attributes/new",
                    fragmentName = "attribute-form",
                    formTarget = "#create-attribute-form",
                    formData = result,
                ) {
                    "tenantId" to tenantId.key
                    "authoredCatalogs" to authoredCatalogs(tenantId)
                    "codeLists" to ListCodeLists(tenantId).query()
                }
                onNonHtmx {
                    page(422, "attributes/list") {
                        attributePageModel(tenantId)
                        "openDialog" to true
                        "authoredCatalogs" to authoredCatalogs(tenantId)
                        "codeLists" to ListCodeLists(tenantId).query()
                        "formData" to result.formData
                        "errors" to result.errors
                    }
                }
            }
        }

        // Success: close the dialog + refresh the list out-of-band (stay on the
        // list). Global attributes the list fragment needs (`auth` for the row
        // edit/delete controls) are injected by HtmxFragmentModelContributor on
        // the OOB render path.
        val attributes = ListAttributeDefinitions(tenantId = tenantId).query()
        return request.htmx {
            dialogSuccess("attributes/list", "attribute-list") {
                "tenantId" to tenantId.key
                "attributes" to attributes
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/attributes") }
        }
    }

    fun editForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val attributeId = request.attributeId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val attribute = GetAttributeDefinition(
            id = attributeId,
        ).query() ?: return ServerResponse.notFound().build()
        val codeLists = ListCodeLists(tenantId).query()

        return request.htmx {
            fragment("attributes/list", "edit-attribute-form") {
                "tenantId" to tenantId.key
                "attribute" to attribute
                "codeLists" to codeLists
            }
        }
    }

    fun update(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val attributeId = request.attributeId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val form = request.form {
            field("displayName") {
                required()
                maxLength(100)
            }
            field("constraintKind") {}
            field("allowedValues") {}
            field("codeList") {}
        }

        val codeLists = ListCodeLists(tenantId).query()

        if (form.hasErrors()) {
            val attribute = GetAttributeDefinition(
                id = attributeId,
            ).query() ?: return ServerResponse.notFound().build()
            return request.htmx {
                fragment("attributes/list", "edit-attribute-form") {
                    "tenantId" to tenantId.key
                    "attribute" to attribute
                    "codeLists" to codeLists
                    "error" to form.errors.values.firstOrNull()
                }
                retarget("#edit-attribute-dialog-body")
                reswap(HxSwap.INNER_HTML)
            }
        }

        val displayName = form["displayName"]
        val constraintKind = form.formData["constraintKind"]?.ifBlank { null } ?: "free"
        val allowedValuesInput = request.params().getFirst("allowedValues")?.trim().orEmpty()
        val codeListSelection = request.params().getFirst("codeList")?.ifBlank { null }
        val (allowedValues, codeListId) = parseConstraint(constraintKind, allowedValuesInput, codeListSelection, tenantId)

        try {
            UpdateAttributeDefinition(
                id = attributeId,
                displayName = displayName,
                allowedValues = allowedValues,
                codeListId = codeListId,
            ).execute() ?: return ServerResponse.notFound().build()
        } catch (e: Exception) {
            val attribute = GetAttributeDefinition(
                id = attributeId,
            ).query() ?: return ServerResponse.notFound().build()
            return request.htmx {
                fragment("attributes/list", "edit-attribute-form") {
                    "tenantId" to tenantId.key
                    "attribute" to attribute
                    "codeLists" to codeLists
                    "error" to (e.message ?: "Update failed")
                }
                retarget("#edit-attribute-dialog-body")
                reswap(HxSwap.INNER_HTML)
            }
        }

        val attributes = ListAttributeDefinitions(tenantId = tenantId).query()
        return request.htmx {
            fragment("attributes/list", "rows") {
                "tenantId" to tenantId.key
                "attributes" to attributes
            }
            trigger("closeDialog")
            onNonHtmx { redirect("/tenants/${tenantId.key}/attributes") }
        }
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val attributeId = request.attributeId(tenantId)
            ?: return ServerResponse.badRequest().build()

        DeleteAttributeDefinition(
            id = attributeId,
        ).execute()

        // Refresh the whole list region so the last delete flips to the empty
        // state (the empty-state lives in `attribute-list`, outside the rows
        // tbody). Direct targeted swap into #attribute-list (outerHTML), not an
        // OOB swap, so `oob` stays unset and hx-swap-oob renders null.
        val attributes = ListAttributeDefinitions(tenantId = tenantId).query()
        return request.htmx {
            fragment("attributes/list", "attribute-list") {
                "tenantId" to tenantId.key
                "attributes" to attributes
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/attributes") }
        }
    }

    /** The catalogs an attribute can be created in — authored ones only. */
    private fun authoredCatalogs(tenantId: TenantId) = ListCatalogs(tenantId.key).query().filter { it.type == CatalogType.AUTHORED }

    /**
     * The full-page list model, used by the newForm / create non-HTMX branches so
     * the list renders behind the embedded create dialog. `authoredCatalogs` (the
     * dialog's catalog `<select>` source) is threaded separately by the callers —
     * the list already puts *all* `catalogs` in the model for its filter, so the
     * dialog uses a distinct key to avoid rendering the wrong (non-authored) options.
     */
    private fun ModelBuilder.attributePageModel(tenantId: TenantId) {
        "pageTitle" to "Attributes - Epistola"
        "tenant" to GetTenant(tenantId.key).query()
        "tenantId" to tenantId.key
        "catalogs" to ListCatalogs(tenantId.key).query()
        "selectedCatalog" to ""
        "attributes" to ListAttributeDefinitions(tenantId = tenantId).query()
    }

    private fun parseAllowedValues(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        return input.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * Translates the form's constraint-kind selection into the
     * `(allowedValues, codeListId)` pair expected by the CRUD commands.
     *
     * `codeListSelection` is the form's `codeList` field — encoded as
     * "<catalog>/<slug>" — so we can pack the user's choice across catalogs
     * into a single `<select>`. Returns `null` for `codeListId` on invalid
     * input; the command's own validation will then reject blank slugs etc.
     */
    private fun parseConstraint(
        constraintKind: String,
        allowedValuesInput: String,
        codeListSelection: String?,
        tenantId: TenantId,
    ): Pair<List<String>, CodeListId?> = when (constraintKind) {
        "inline" -> parseAllowedValues(allowedValuesInput) to null
        "code-list" -> emptyList<String>() to parseCodeListSelection(codeListSelection, tenantId)
        else -> emptyList<String>() to null // free format
    }

    private fun parseCodeListSelection(selection: String?, tenantId: TenantId): CodeListId? {
        val (catalogPart, slugPart) = selection?.split('/', limit = 2)?.takeIf { it.size == 2 } ?: return null
        val catalogKey = CatalogKey.validateOrNull(catalogPart) ?: return null
        val slug = CodeListKey.validateOrNull(slugPart) ?: return null
        return CodeListId(slug, CatalogId(catalogKey, tenantId))
    }
}
