// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.namespaces.GrantTenantNamespace
import app.epistola.suite.catalog.namespaces.ListNamespaceGrants
import app.epistola.suite.catalog.namespaces.NamespaceGrant
import app.epistola.suite.catalog.namespaces.RevokeTenantNamespace
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.htmx.FormData
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.TenantNotFoundException
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.validation.ValidationException
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * The namespaces a tenant holds (ADR 0034, section 3), managed by a platform administrator on a
 * platform page reached from the tenant list, beside the rest of tenant administration: a platform
 * administrator need not be a member of the tenant. Every route dispatches through the mediator,
 * whose `TENANT_MANAGER` gate on [ListNamespaceGrants], [GrantTenantNamespace] and
 * [RevokeTenantNamespace] answers anyone else with the standard 403 page.
 */
@Component
class TenantNamespaceHandler(
    private val identity: CatalogIdentityProperties,
) {
    fun page(request: ServerRequest): ServerResponse {
        val tenant = tenant(request)
        return namespacesPage(tenant, ListNamespaceGrants(tenant.id).query(), openDialog = false)
    }

    /** The grant dialog: swapped into the page's mount, or the page with it open when visited directly. */
    fun newForm(request: ServerRequest): ServerResponse {
        val tenant = tenant(request)
        val grants = ListNamespaceGrants(tenant.id).query()
        if (!request.isHtmx) return namespacesPage(tenant, grants, openDialog = true)
        return request.htmx {
            fragment("platform/namespaces", "grant-dialog") {
                "tenant" to tenant
                "defaultAuthority" to identity.defaultRegistry
            }
        }
    }

    fun grant(request: ServerRequest): ServerResponse {
        val tenant = tenant(request)
        val form = request.form {
            field("authority") {
                required()
                maxLength(CatalogNamespaces.REGISTRY_MAX_LENGTH)
            }
            field("namespace") {
                required()
                pattern(CatalogNamespaces.PATTERN)
                minLength(CatalogNamespaces.MIN_LENGTH)
                maxLength(CatalogNamespaces.MAX_LENGTH)
            }
        }
        val result = if (form.hasErrors()) {
            form
        } else {
            form.executeOrFormError { GrantTenantNamespace(tenant.id, form["authority"].trim(), form["namespace"].trim()).execute() }
        }
        val grants = ListNamespaceGrants(tenant.id).query()
        if (result.hasErrors()) {
            if (!request.isHtmx) return namespacesPage(tenant, grants, openDialog = true, formData = result, status = 422)
            return request.htmx {
                dialogFieldErrors(template = "platform/namespaces", fragmentName = "grant-form", formTarget = "#grant-namespace-form", formData = result) {
                    "tenant" to tenant
                    "defaultAuthority" to identity.defaultRegistry
                }
            }
        }
        if (!request.isHtmx) return redirect(pageUrl(tenant.id))
        return request.htmx {
            dialogSuccess("platform/namespaces", "namespace-section", pageUrl(tenant.id)) {
                "tenant" to tenant
                "grants" to grants
            }
        }
    }

    /** Revokes after the confirmation; a refusal (catalogs still named in it) is said in the section. */
    fun revoke(request: ServerRequest): ServerResponse {
        val tenant = tenant(request)
        val error = try {
            RevokeTenantNamespace(tenant.id, request.param("authority").orElse("").trim(), request.param("namespace").orElse("").trim()).execute()
            null
        } catch (e: ValidationException) {
            e.message
        }
        if (!request.isHtmx) return redirect(pageUrl(tenant.id))
        val grants = ListNamespaceGrants(tenant.id).query()
        return request.htmx {
            fragment("platform/namespaces", "namespace-section") {
                "tenant" to tenant
                "grants" to grants
                if (error != null) "revokeError" to error
            }
        }
    }

    private fun namespacesPage(
        tenant: Tenant,
        grants: List<NamespaceGrant>,
        openDialog: Boolean,
        formData: FormData? = null,
        status: Int = 200,
    ): ServerResponse = ServerResponse.status(status).render(
        "platform/namespaces",
        buildMap {
            put("pageTitle", "Namespaces of ${tenant.name} - Epistola")
            put("tenant", tenant)
            put("grants", grants)
            put("defaultAuthority", identity.defaultRegistry)
            if (openDialog) put("openDialog", true)
            if (formData != null) {
                put("formData", formData.formData)
                put("errors", formData.errors)
            }
        },
    )

    /** The tenant in the path, read through the platform's list, since the administrator need not be a member. */
    private fun tenant(request: ServerRequest): Tenant {
        val key = TenantKey.of(request.pathVariable("tenantId"))
        return ListTenants(idPrefix = key.value).query().firstOrNull { it.id == key } ?: throw TenantNotFoundException(key)
    }

    private fun pageUrl(tenantKey: TenantKey) = "/platform/tenants/${tenantKey.value}/namespaces"
}
