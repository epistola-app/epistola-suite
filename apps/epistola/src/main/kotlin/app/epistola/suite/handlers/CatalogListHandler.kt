// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeleteCatalog
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.namespaces.ListHeldNamespaces
import app.epistola.suite.catalog.namespaces.LocalAuthority
import app.epistola.suite.catalog.namespaces.NamespaceName
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.handlers.CatalogViews.catalogListModel
import app.epistola.suite.handlers.CatalogViews.catalogPageModel
import app.epistola.suite.handlers.CatalogViews.listWithError
import app.epistola.suite.handlers.CatalogViews.section
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.catalog
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.redirect
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import app.epistola.suite.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/** The catalogs list: creating a catalog, subscribing to one, deleting one. */
@Component
class CatalogListHandler {
    // Logs under CatalogHandler, the name these lines had before the handler was split.
    private val logger = LoggerFactory.getLogger(CatalogHandler::class.java)

    fun list(request: ServerRequest): ServerResponse {
        val saved = request.param("saved").isPresent

        return ServerResponse.ok().page("catalogs/list") {
            "pageTitle" to "Catalogs - Epistola"
            "activeNavSection" to "catalogs"
            catalogListModel(request)
            if (saved) "saved" to true
        }
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_MANAGE)
        val namespaces = ListHeldNamespaces(tenantId.key).query()
        return request.htmx {
            // In-app trigger (hx-get → #dialog-mount): just the dialog fragment.
            fragment("catalogs/new", "dialog") {
                "tenantId" to tenantId.key
                "namespaces" to namespaces
            }
            // Direct navigation / boost: the host list page with the create
            // dialog embedded in its mount (openDialog=true), opened on load by JS.
            onFullPage {
                page("catalogs/list") {
                    catalogPageModel(request)
                    "namespaces" to namespaces
                    "openDialog" to true
                    "openDialogFragment" to "catalogs/new :: dialog"
                }
            }
        }
    }

    fun createCatalog(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_MANAGE)

        val form = request.form {
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(50)
                asCatalogId()
            }
            field("name") {
                required()
            }
        }

        // Field validation (incl. slug/CatalogKey) and the command-level failure
        // (duplicate slug) both land as `errors` on the FormData, so they share one
        // error path — mirroring the environment/attribute conversions.
        var createdNamespace: String? = null
        val result = if (form.hasErrors()) {
            form
        } else {
            form.executeOrFormError {
                // Named where the person chose (ADR 0034, section 3): a held namespace, or local with one of
                // its own; nothing chosen leaves it to the command, which takes the one the tenant holds.
                val choice = form["namespaceChoice"].trim()
                val local = LocalAuthority.isLocal(choice)
                val held = if (local) null else NamespaceName.parse(choice)
                createdNamespace = CreateCatalog(
                    tenantKey = tenantId.key,
                    id = CatalogKey.of(form["slug"]),
                    name = form["name"],
                    registry = if (local) LocalAuthority.NAME else held?.authority,
                    namespace = if (local) form["localNamespace"].trim().ifEmpty { null } else held?.namespace,
                ).execute().namespace
            }
        }
        val namespaces = ListHeldNamespaces(tenantId.key).query()

        if (result.hasErrors()) {
            return request.htmx {
                // Re-render the form inside the dialog (retargeted to the form, not
                // the list) with inline errors + preserved values.
                dialogFieldErrors(
                    template = "catalogs/new",
                    fragmentName = "catalog-form",
                    formTarget = "#create-catalog-form",
                    formData = result,
                ) {
                    "tenantId" to tenantId.key
                    "namespaces" to namespaces
                }
                onFullPage {
                    page(422, "catalogs/list") {
                        catalogPageModel(request)
                        "namespaces" to namespaces
                        "openDialog" to true
                        "openDialogFragment" to "catalogs/new :: dialog"
                        "formData" to result.formData
                        "errors" to result.errors
                    }
                }
            }
        }

        // Success: on to the new catalog's page, where its next step is waiting (New template)
        // rather than back to the list it was created from.
        // Its address names its namespace when another has a catalog with its key (ADR 0034, phase 4).
        // Its address names its namespace (ADR 0034, section 9).
        val created = CatalogPages.browse(tenantId, CatalogSegment(CatalogKey.of(form["slug"]), namespace = createdNamespace))
        return request.htmx {
            dialogLocation(created)
            onFullPage { redirect(created) }
        }
    }

    fun registerForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_MANAGE)
        return request.htmx {
            // In-app trigger (hx-get → #dialog-mount): just the dialog fragment.
            fragment("catalogs/subscribe", "dialog") {
                "tenantId" to tenantId.key
            }
            // Direct navigation / boost: the host list page with the subscribe
            // dialog embedded in its mount (openDialog=true), opened on load by JS.
            onFullPage {
                page("catalogs/list") {
                    catalogPageModel(request)
                    "openDialog" to true
                    "openDialogFragment" to "catalogs/subscribe :: dialog"
                }
            }
        }
    }

    fun register(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_MANAGE)

        val form = request.form {
            field("sourceUrl") {
                required()
                maxLength(2000)
            }
        }

        if (form.hasErrors()) {
            return registerError(request, "Catalog URL is required.")
        }

        val sourceUrl = form.formData["sourceUrl"]!!
        val authTypeStr = form.formData["authType"] ?: "NONE"
        val authType = try {
            AuthType.valueOf(authTypeStr)
        } catch (_: IllegalArgumentException) {
            AuthType.NONE
        }
        val authCredential = form.formData["authCredential"]?.ifBlank { null }

        return try {
            RegisterCatalog(
                tenantKey = tenantId.key,
                sourceUrl = sourceUrl,
                authType = authType,
                authCredential = authCredential,
            ).execute()

            // Stay-on-list: close the dialog + OOB-refresh the list with the new catalog.
            request.htmx {
                dialogSuccess("catalogs/list", "catalog-list", "/tenants/${tenantId.key}/catalogs") {
                    catalogListModel(request)
                }
                onFullPage { redirect("/tenants/${tenantId.key}/catalogs?saved=true") }
            }
        } catch (e: ValidationException) {
            // The URL itself was refused (scheme, file roots, address): say why, it is the user's input.
            registerError(request, "Failed to register catalog: ${e.message}")
        } catch (e: Exception) {
            logger.warn("Failed to register catalog: ${e.message}", e)
            registerError(request, "Failed to register catalog. Check that the URL points to a valid catalog manifest.")
        }
    }

    /**
     * Register error → OOB-update just the dialog's form-error slot, leaving the
     * live form (URL, auth type, credential) exactly as the user left it.
     * dialogFieldErrors would re-render the body and reset the authType <select>
     * / credential toggle, so we keep the operational failure text-only here.
     */
    private fun registerError(request: ServerRequest, message: String): ServerResponse = request.htmx {
        dialogFormError("subscribe-catalog-error", message)
        // The subscribe form keeps a real no-JS fallback (method="post" th:action),
        // so this branch is reachable — re-embed the dialog rather than dropping the
        // user on a bare list with their URL/auth input gone. The dialog's
        // form-error slot renders the message from the standardized `error` key
        // (see epistola-web/form-error), so it shows INSIDE the reopened dialog
        // instead of only in the list banner behind the modal backdrop.
        onFullPage {
            page(422, "catalogs/list") {
                catalogPageModel(request)
                "openDialog" to true
                "openDialogFragment" to "catalogs/subscribe :: dialog"
                "error" to message
            }
        }
    }

    fun deleteCatalog(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalog = request.catalog(tenantId)

        return try {
            DeleteCatalog(catalog = catalog).execute()

            return request.htmx {
                fragment("catalogs/list", "catalog-list") {
                    catalogListModel(request)
                }
                onFullPage {
                    redirect("/tenants/${tenantId.key}/catalogs")
                }
            }
        } catch (e: app.epistola.suite.catalog.CatalogInUseException) {
            if (request.isHtmx) {
                ServerResponse.status(422)
                    .header("Content-Type", "application/json")
                    .body(mapOf("error" to (e.message ?: "Catalog is in use by other catalogs")))
            } else {
                listWithError(request, e.message ?: "Catalog is in use by other catalogs")
            }
        } catch (e: Exception) {
            logger.warn("Failed to unregister catalog: ${e.message}", e)
            if (request.isHtmx) {
                ServerResponse.status(422)
                    .header("Content-Type", "application/json")
                    .body(mapOf("error" to (e.message ?: "Failed to remove catalog.")))
            } else {
                listWithError(request, e.message ?: "Failed to remove catalog.")
            }
        }
    }
}
