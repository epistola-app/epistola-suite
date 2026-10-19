// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes

import app.epistola.suite.attributes.codelists.queries.ListCodeLists
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.commands.DeleteAttributeDefinition
import app.epistola.suite.attributes.commands.UpdateAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.handlers.CatalogDraftBar
import app.epistola.suite.handlers.CatalogPages
import app.epistola.suite.handlers.FromOtherCatalog
import app.epistola.suite.handlers.FromOtherCatalogs
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.attributeId
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.catalogParam
import app.epistola.suite.htmx.catalogSegment
import app.epistola.suite.htmx.catalogVersion
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
        // The filter may name a draft (`letters@2.0.0`); its rows are then the draft's.
        val segment = request.catalogSegment()
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()
        val catalogs = ListCatalogs(tenantId.key).query()
        val attributes = ListAttributeDefinitions(tenantId = tenantId, catalogKey = segment.key, catalogVersion = segment.ref(tenantId.key)).query()
        val bar = segment.let { draftBarFor(tenantId, it) }
        // What the catalogs this one depends on hold of this kind, listed read-only below its own.
        val fromOtherCatalogs = FromOtherCatalogs.of(bar, { ListAttributeDefinitions(tenantId = tenantId, catalogKey = it).query() }) { a, c, n -> FromOtherCatalog(a.displayName, "/tenants/${tenantId.key}/catalogs/${c.value}/attributes", n) }
        return ServerResponse.ok().page("attributes/list") {
            "pageTitle" to "Attributes - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "catalogs" to catalogs
            "selectedCatalog" to segment.value
            "draftSegment" to segment
            "draftBar" to bar
            "fromOtherCatalogs" to fromOtherCatalogs
            "attributes" to attributes
        }
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        // Fragment models are lazy, so only the branch that renders evaluates —
        // one ListCatalogs and one ListCodeLists per request, shared by the list
        // filter and the dialog's two <select>s.
        val allCatalogs by lazy { ListCatalogs(tenantId.key).query() }
        val authoredCatalogs by lazy { allCatalogs.filter { it.type == CatalogType.AUTHORED } }
        // Opened from a list filtered to a draft, the dialog creates in that draft.
        val selected = request.catalogParam()
        val codeLists by lazy { ListCodeLists(tenantId, catalogVersion = selected?.ref(tenantId.key)).query() }
        return request.htmx {
            // In-app trigger (hx-get → #dialog-mount): just the dialog fragment.
            fragment("attributes/new", "dialog") {
                "tenantId" to tenantId.key
                "authoredCatalogs" to authoredCatalogs
                "codeLists" to codeLists
                // The catalog it was opened on, a draft or plain (see ThemeHandler.newForm).
                "draftSegment" to selected
            }
            // Direct navigation / boost: the host list page with the dialog
            // embedded in its mount (openDialog=true), opened on load by the JS.
            onNonHtmx {
                page("attributes/list") {
                    attributePageModel(tenantId, allCatalogs)
                    "openDialog" to true
                    "authoredCatalogs" to authoredCatalogs
                    "codeLists" to codeLists
                }
            }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)

        val form = request.form {
            // Validated like any other field so a missing or malformed catalog
            // lands in the dialog's error path rather than a bodyless 400 (which
            // HTMX does not swap) or a CatalogKey.of throw (a 500).
            field("catalog") {
                required()
                asCatalogId()
            }
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

        // Constraint parsing is preserved verbatim: constraintKind defaults to
        // "free"; the inline/code-list panes feed parseConstraint → the command.
        val constraintKind = form.formData["constraintKind"]?.ifBlank { null } ?: "free"
        val allowedValuesInput = request.params().getFirst("allowedValues")?.trim().orEmpty()
        val codeListSelection = request.params().getFirst("codeList")?.ifBlank { null }
        val (allowedValues, codeListId) = parseConstraint(constraintKind, allowedValuesInput, codeListSelection, tenantId)
        val catalogSegment = CatalogSegment.parseOrNull(form.formData["catalog"])

        // Field validation (incl. slug/AttributeKey) and the command-level failure
        // (duplicate slug) both land as `errors` on the FormData, so they share one
        // error path — mirroring EnvironmentHandler.create.
        val result = if (form.hasErrors()) {
            form
        } else {
            form.executeOrFormError {
                CreateAttributeDefinition(
                    // Safe !!/of(): required() rejected blank values and
                    // asAttributeId()/asCatalogId() rejected malformed ones, so this
                    // is reached only with a valid slug and catalog.
                    id = AttributeId(
                        AttributeKey.validateOrNull(form["slug"])!!,
                        CatalogId(catalogSegment!!.key, tenantId),
                    ),
                    displayName = form["displayName"],
                    allowedValues = allowedValues,
                    codeListId = codeListId,
                    catalogVersion = catalogSegment.ref(tenantId.key),
                ).execute()
            }
        }

        if (result.hasErrors()) {
            // Prefill shared by both render paths — one catalog + code-list query.
            val allCatalogs = ListCatalogs(tenantId.key).query()
            val authored = allCatalogs.filter { it.type == CatalogType.AUTHORED }
            val codeLists = ListCodeLists(tenantId).query()
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
                    "authoredCatalogs" to authored
                    "codeLists" to codeLists
                }
                onNonHtmx {
                    page(422, "attributes/list") {
                        attributePageModel(tenantId, allCatalogs)
                        "openDialog" to true
                        "authoredCatalogs" to authored
                        "codeLists" to codeLists
                        "formData" to result.formData
                        "errors" to result.errors
                    }
                }
            }
        }

        // Success: soft-navigate to the (unfiltered) attribute list via a boosted
        // body-swap (HX-Location) — no full-page reload. Attributes have no page of
        // their own, so we return to the list; a full boosted GET re-renders the
        // whole list fresh (not an OOB partial), which keeps the visible rows in
        // step with the URL and sidesteps the catalog-filter desync an OOB
        // stay-on-list refresh would cause. onNonHtmx 303-redirects for the
        // non-HTMX fallback.
        // The list of the catalog (or draft) the attribute was created in, which is where it is.
        val destination = "/tenants/${tenantId.key}/catalogs/${catalogSegment!!.value}/attributes"
        return request.htmx {
            dialogLocation(destination)
            onNonHtmx { redirect(destination) }
        }
    }

    fun editForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val attributeId = request.attributeId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val version = request.catalogVersion()
        val attribute = GetAttributeDefinition(
            id = attributeId,
            catalogVersion = version,
        ).query() ?: return ServerResponse.notFound().build()
        val codeLists = ListCodeLists(tenantId, catalogVersion = version).query()

        return request.htmx {
            fragment("attributes/list", "edit-attribute-form") {
                "tenantId" to tenantId.key
                "attribute" to attribute
                "codeLists" to codeLists
                "draftSegment" to request.catalogSegment().takeIf { it.draft != null }
            }
        }
    }

    fun update(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
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

        val version = request.catalogVersion()
        val draftSegment = request.catalogSegment().takeIf { it.draft != null }
        val codeLists = ListCodeLists(tenantId, catalogVersion = version).query()

        if (form.hasErrors()) {
            val attribute = GetAttributeDefinition(
                id = attributeId,
                catalogVersion = version,
            ).query() ?: return ServerResponse.notFound().build()
            return request.htmx {
                fragment("attributes/list", "edit-attribute-form") {
                    "tenantId" to tenantId.key
                    "attribute" to attribute
                    "codeLists" to codeLists
                    "draftSegment" to draftSegment
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
                catalogVersion = version,
            ).execute() ?: return ServerResponse.notFound().build()
        } catch (e: Exception) {
            val attribute = GetAttributeDefinition(
                id = attributeId,
                catalogVersion = version,
            ).query() ?: return ServerResponse.notFound().build()
            return request.htmx {
                fragment("attributes/list", "edit-attribute-form") {
                    "tenantId" to tenantId.key
                    "attribute" to attribute
                    "codeLists" to codeLists
                    "draftSegment" to draftSegment
                    "error" to (e.message ?: "Update failed")
                }
                retarget("#edit-attribute-dialog-body")
                reswap(HxSwap.INNER_HTML)
            }
        }

        // Back to the list as it was shown: the draft's attributes when it was filtered to one.
        val attributes = ListAttributeDefinitions(tenantId = tenantId, catalogKey = draftSegment?.key, catalogVersion = version).query()
        return request.htmx {
            fragment("attributes/list", "rows") {
                "tenantId" to tenantId.key
                "attributes" to attributes
                "draftSegment" to draftSegment
            }
            trigger("closeDialog")
            onNonHtmx { redirect(CatalogPages.list(request, tenantId.key, "attributes")) }
        }
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val attributeId = request.attributeId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val version = request.catalogVersion()
        val draftSegment = request.catalogSegment().takeIf { it.draft != null }
        DeleteAttributeDefinition(
            id = attributeId,
            catalogVersion = version,
        ).execute()

        // Refresh the whole list region so the last delete flips to the empty
        // state (the empty-state lives in `attribute-list`, outside the rows
        // tbody). Direct targeted swap into #attribute-list (outerHTML), not an
        // OOB swap, so `oob` stays unset and hx-swap-oob renders null.
        val attributes = ListAttributeDefinitions(tenantId = tenantId, catalogKey = draftSegment?.key, catalogVersion = version).query()
        return request.htmx {
            fragment("attributes/list", "attribute-list") {
                "tenantId" to tenantId.key
                "attributes" to attributes
                "draftSegment" to draftSegment
            }
            onNonHtmx { redirect(CatalogPages.list(request, tenantId.key, "attributes")) }
        }
    }

    /**
     * The full-page list model, used by the newForm / create non-HTMX branches so
     * the list renders behind the embedded create dialog. `authoredCatalogs` (the
     * dialog's catalog `<select>` source) is threaded separately by the callers —
     * the list already puts *all* `catalogs` in the model for its filter, so the
     * dialog uses a distinct key to avoid rendering the wrong (non-authored) options.
     */
    private fun ModelBuilder.attributePageModel(
        tenantId: TenantId,
        catalogs: List<Catalog>,
    ) {
        "pageTitle" to "Attributes - Epistola"
        "tenant" to GetTenant(tenantId.key).query()
        "tenantId" to tenantId.key
        "catalogs" to catalogs
        "selectedCatalog" to ""
        "attributes" to ListAttributeDefinitions(tenantId = tenantId).query()
    }

    private fun draftBarFor(tenantId: TenantId, segment: CatalogSegment): CatalogDraftBar = CatalogDraftBar.of(tenantId.key, segment) { "/tenants/${tenantId.key}/catalogs/${it.value}/attributes" }

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
