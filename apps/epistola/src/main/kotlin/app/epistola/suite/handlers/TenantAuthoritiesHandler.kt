// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.suite.catalog.namespaces.GetTenantAuthorities
import app.epistola.suite.catalog.namespaces.GrantTenantNamespace
import app.epistola.suite.catalog.namespaces.RevokeTenantNamespace
import app.epistola.suite.catalog.namespaces.TenantAuthorities
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.exchange.GetExchangeSettings
import app.epistola.suite.htmx.FormData
import app.epistola.suite.htmx.ModelBuilder
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
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Settings, Authorities & namespaces (ADR 0034, amendment of 2026-11-03): the authorities the tenant
 * is connected to and the namespaces each grants it, for the tenant's own administrator
 * (`INTEGRATIONS_MANAGE`). `local` is always there; the Exchange's namespaces come from the Exchange
 * connection, managed on its own page; a domain's are added and removed here.
 */
@Component
class TenantAuthoritiesHandler {

    fun page(request: ServerRequest): ServerResponse {
        val tenantKey = gate(request)
        return authoritiesPage(tenantKey, openDialog = false)
    }

    /** The add dialog: swapped into the page's mount, or the page with it open when visited directly. */
    fun newForm(request: ServerRequest): ServerResponse {
        val tenantKey = gate(request)
        if (!request.isHtmx) return authoritiesPage(tenantKey, openDialog = true)
        val authorities = GetTenantAuthorities(tenantKey).query()
        return request.htmx {
            fragment("authorities", "grant-dialog") { dialogModel(tenantKey, authorities) }
        }
    }

    fun grant(request: ServerRequest): ServerResponse {
        val tenantKey = gate(request)
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
            form.executeOrFormError { GrantTenantNamespace(tenantKey, form["authority"].trim().lowercase(), form["namespace"].trim()).execute() }
        }
        val authorities = GetTenantAuthorities(tenantKey).query()
        if (result.hasErrors()) {
            if (!request.isHtmx) return authoritiesPage(tenantKey, openDialog = true, formData = result, status = 422)
            return request.htmx {
                dialogFieldErrors(template = "authorities", fragmentName = "grant-form", formTarget = "#grant-namespace-form", formData = result) {
                    dialogModel(tenantKey, authorities)
                }
            }
        }
        if (!request.isHtmx) return redirect(pageUrl(tenantKey))
        return request.htmx {
            dialogSuccess("authorities", "authorities-section", pageUrl(tenantKey)) { sectionModel(tenantKey, authorities) }
        }
    }

    /** Removes after the confirmation; a refusal (catalogs still named in it, or the connection's) is said in the section. */
    fun revoke(request: ServerRequest): ServerResponse {
        val tenantKey = gate(request)
        val error = try {
            RevokeTenantNamespace(tenantKey, request.param("authority").orElse("").trim(), request.param("namespace").orElse("").trim()).execute()
            null
        } catch (e: ValidationException) {
            e.message
        }
        if (!request.isHtmx) return redirect(pageUrl(tenantKey))
        val authorities = GetTenantAuthorities(tenantKey).query()
        return request.htmx {
            fragment("authorities", "authorities-section") {
                sectionModel(tenantKey, authorities)
                if (error != null) "revokeError" to error
            }
        }
    }

    private fun authoritiesPage(
        tenantKey: TenantKey,
        openDialog: Boolean,
        formData: FormData? = null,
        status: Int = 200,
    ): ServerResponse {
        val authorities = GetTenantAuthorities(tenantKey).query()
        return ServerResponse.status(status).page("authorities") {
            "pageTitle" to "Authorities & namespaces - Epistola"
            "activeNavSection" to "authorities"
            sectionModel(tenantKey, authorities)
            dialogModel(tenantKey, authorities)
            if (openDialog) {
                "openDialog" to true
            }
            if (formData != null) {
                "formData" to formData.formData
                "errors" to formData.errors
            }
        }
    }

    private fun ModelBuilder.sectionModel(tenantKey: TenantKey, authorities: TenantAuthorities) {
        "tenantId" to tenantKey
        "authorities" to authorities
        "exchange" to GetExchangeSettings(tenantKey).query()
    }

    private fun ModelBuilder.dialogModel(tenantKey: TenantKey, authorities: TenantAuthorities) {
        "tenantId" to tenantKey
        "domainNames" to authorities.domainNames
    }

    private fun gate(request: ServerRequest): TenantKey {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.INTEGRATIONS_MANAGE)
        return tenantKey
    }

    private fun pageUrl(tenantKey: TenantKey) = "/tenants/${tenantKey.value}/authorities"
}
