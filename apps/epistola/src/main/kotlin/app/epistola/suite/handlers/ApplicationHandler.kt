// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.apikeys.commands.RevokeApiKey
import app.epistola.suite.apikeys.queries.ListApiKeys
import app.epistola.suite.applications.Application
import app.epistola.suite.applications.MAX_APPLICATION_DESCRIPTION_LENGTH
import app.epistola.suite.applications.commands.CreateApplication
import app.epistola.suite.applications.commands.DeleteApplication
import app.epistola.suite.applications.commands.UpdateApplication
import app.epistola.suite.applications.queries.GetApplication
import app.epistola.suite.applications.queries.ListApplications
import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.common.ids.ApplicationKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.htmx.FormData
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.redirect
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.security.requirePermission
import app.epistola.suite.validation.ValidationException
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * The tenant's applications and their API keys (ADR 0054). An application is a calling system: it
 * holds the roles its keys act with and owns the documents it requests. Keys are created and
 * revoked on the application's page, so rotating one never changes who owns the documents.
 */
@Component
class ApplicationHandler {

    fun list(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        return ServerResponse.ok().page("applications/list") { listModel(tenantKey) }
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.CREDENTIALS_MANAGE)
        return request.htmx {
            fragment("applications/new", "dialog") { newFormModel(tenantKey) }
            onFullPage {
                page("applications/list") {
                    listModel(tenantKey)
                    newFormModel(tenantKey)
                    "openDialog" to true
                }
            }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.CREDENTIALS_MANAGE)

        val form = request.form {
            field("name") {
                required()
                maxLength(100)
            }
            field("description") { maxLength(MAX_APPLICATION_DESCRIPTION_LENGTH) }
        }
        val selectedRoleNames = request.params()["roles"].orEmpty()
        val roles = selectedRoleNames.mapNotNull { name -> runCatching { TenantRole.valueOf(name) }.getOrNull() }.toSet()
        val seesAllDocuments = request.param("seesAllDocuments").map { it == "true" }.orElse(false)

        val errors = form.errors.toMutableMap()
        if (roles.isEmpty()) errors["roles"] = "Select at least one role"
        if ((roles - grantableRoles(tenantKey)).isNotEmpty()) errors["roles"] = "You can only grant roles you hold in this tenant"

        val created = if (errors.isEmpty()) {
            try {
                CreateApplication(tenantKey, form["name"], form["description"], roles, seesAllDocuments).execute()
            } catch (e: ValidationException) {
                errors[e.field] = e.message
                null
            }
        } else {
            null
        }

        if (created == null) {
            val formData = FormData(form.formData, errors)
            return request.htmx {
                dialogFieldErrors(
                    template = "applications/new",
                    fragmentName = "application-form",
                    formTarget = "#create-application-form",
                    formData = formData,
                ) {
                    newFormModel(tenantKey)
                    "selectedRoles" to selectedRoleNames
                    "seesAllDocuments" to seesAllDocuments
                }
                onFullPage {
                    page(422, "applications/list") {
                        listModel(tenantKey)
                        newFormModel(tenantKey)
                        "openDialog" to true
                        "selectedRoles" to selectedRoleNames
                        "seesAllDocuments" to seesAllDocuments
                        "formData" to formData.formData
                        "errors" to errors
                    }
                }
            }
        }

        // A new application has no key yet: its page is where the first one is created.
        val detailUrl = detailUrl(tenantKey, created.id)
        return request.htmx {
            dialogRedirect(detailUrl)
            onFullPage { redirect(detailUrl) }
        }
    }

    fun detail(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        val application = application(request) ?: return ServerResponse.notFound().build()
        return ServerResponse.ok().page("applications/detail") { detailModel(tenantKey, application) }
    }

    /** Switches whether the application sees every document of the tenant, or only its own. */
    fun updateDocuments(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.CREDENTIALS_MANAGE)
        val application = application(request) ?: return ServerResponse.notFound().build()
        val seesAll = request.param("seesAllDocuments").map { it == "true" }.orElse(false)
        UpdateApplication(tenantKey, application.id, application.name, application.description, seesAll).execute()
        return redirect(detailUrl(tenantKey, application.id))
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.CREDENTIALS_MANAGE)
        val application = application(request) ?: return ServerResponse.notFound().build()
        DeleteApplication(tenantKey, application.id).execute()
        return redirect("/tenants/$tenantKey/applications")
    }

    fun newKeyForm(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.CREDENTIALS_MANAGE)
        val application = application(request) ?: return ServerResponse.notFound().build()
        return request.htmx {
            fragment("applications/new-key", "dialog") { keyFormModel(tenantKey, application) }
            onFullPage {
                page("applications/detail") {
                    detailModel(tenantKey, application)
                    "openDialog" to true
                }
            }
        }
    }

    fun createKey(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.CREDENTIALS_MANAGE)
        val application = application(request) ?: return ServerResponse.notFound().build()

        val form = request.form {
            field("name") {
                required()
                maxLength(100)
            }
            field("expiresAt") {}
        }
        val expiry = parseExpiresAt(form["expiresAt"])
        val errors = form.errors.toMutableMap()
        expiry.errorOrNull()?.let { errors["expiresAt"] = it }
        if ((application.roles - grantableRoles(tenantKey)).isNotEmpty()) {
            errors["name"] = "This application acts with roles you do not hold in this tenant, so you cannot create a key for it"
        }

        if (errors.isNotEmpty()) {
            val formData = FormData(form.formData, errors)
            return request.htmx {
                dialogFieldErrors(
                    template = "applications/new-key",
                    fragmentName = "key-form",
                    formTarget = "#create-api-key-form",
                    formData = formData,
                ) { keyFormModel(tenantKey, application) }
                onFullPage {
                    page(422, "applications/detail") {
                        detailModel(tenantKey, application)
                        "openDialog" to true
                        "formData" to formData.formData
                        "errors" to errors
                    }
                }
            }
        }

        val result = CreateApiKey(
            tenantId = tenantKey,
            name = form["name"],
            expiresAt = (expiry as ParsedExpiry.Ok).value,
            createdBy = SecurityContext.currentOrNull()?.userId,
            applicationId = application.id,
        ).execute()

        // Success is a reveal, not a redirect: the one-time plaintext key replaces the form and the
        // dialog stays open so it can be copied. The keys behind it are refreshed, so dismissing the
        // dialog any way shows the new key rather than suggesting the create failed.
        return request.htmx {
            dialogReveal(
                template = "applications/key-created",
                fragmentName = "created-reveal",
                revealTarget = "#create-api-key-form",
            ) {
                "tenantId" to tenantKey
                "app" to application
                "plaintextKey" to result.plaintextKey
                "apiKey" to result.apiKey
            }
            oob("applications/detail", "key-list") {
                keyListModel(tenantKey, application.id)
                "oob" to true
            }
            onFullPage {
                page("applications/key-created") {
                    "pageTitle" to "API key created - Epistola"
                    "tenantId" to tenantKey
                    "app" to application
                    "plaintextKey" to result.plaintextKey
                    "apiKey" to result.apiKey
                }
            }
        }
    }

    fun revokeKey(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        val application = application(request) ?: return ServerResponse.notFound().build()
        val apiKeyKey = runCatching { ApiKeyKey.of(request.pathVariable("apiKeyId")) }.getOrNull()
            ?: return ServerResponse.badRequest().build()
        RevokeApiKey(tenantId = tenantKey, id = apiKeyKey, revokedBy = SecurityContext.currentOrNull()?.userId).execute()
        return request.htmx {
            fragment("applications/detail", "key-list") { keyListModel(tenantKey, application.id) }
            onFullPage { redirect(detailUrl(tenantKey, application.id)) }
        }
    }

    /** `/api-keys` predates applications; keys live on their application's page now. */
    fun legacyApiKeys(request: ServerRequest): ServerResponse = redirect("/tenants/${request.tenantId().key}/applications")

    private fun application(request: ServerRequest): Application? {
        val id = runCatching { ApplicationKey.of(request.pathVariable("applicationId")) }.getOrNull() ?: return null
        return GetApplication(request.tenantId().key, id).query()
    }

    private fun detailUrl(tenantKey: TenantKey, id: ApplicationKey) = "/tenants/$tenantKey/applications/$id"

    private fun ModelBuilder.listModel(tenantKey: TenantKey) {
        "pageTitle" to "Applications - Epistola"
        "tenantId" to tenantKey
        "applications" to ListApplications(tenantKey).query()
        "roleLabels" to ROLE_LABELS
    }

    private fun ModelBuilder.newFormModel(tenantKey: TenantKey) {
        "tenantId" to tenantKey
        "roleOptions" to grantableRoleOptions(tenantKey)
    }

    private fun ModelBuilder.detailModel(tenantKey: TenantKey, application: Application) {
        "pageTitle" to "${application.name} - Applications - Epistola"
        "app" to application
        "roleLabels" to ROLE_LABELS
        keyListModel(tenantKey, application.id)
    }

    private fun ModelBuilder.keyListModel(tenantKey: TenantKey, applicationId: ApplicationKey) {
        val application = GetApplication(tenantKey, applicationId).query()
        "tenantId" to tenantKey
        "app" to application
        "apiKeys" to ListApiKeys(tenantKey, applicationId).query().filter { it.enabled }
        "canCreateKey" to (application != null && (application.roles - grantableRoles(tenantKey)).isEmpty())
    }

    private fun ModelBuilder.keyFormModel(tenantKey: TenantKey, application: Application) {
        "tenantId" to tenantKey
        "app" to application
    }

    /** A selectable role on the create form: its enum name plus a human label/description. */
    data class RoleOption(
        val value: String,
        val label: String,
        val description: String,
        val defaultChecked: Boolean,
    )

    private sealed class ParsedExpiry {
        data class Ok(val value: java.time.Instant?) : ParsedExpiry()
        data class Err(val message: String) : ParsedExpiry()

        fun errorOrNull(): String? = (this as? Err)?.message
    }

    private fun parseExpiresAt(raw: String): ParsedExpiry {
        if (raw.isBlank()) return ParsedExpiry.Ok(null)
        return try {
            ParsedExpiry.Ok(LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant())
        } catch (_: DateTimeParseException) {
            ParsedExpiry.Err("Expires must be a valid date (YYYY-MM-DD)")
        }
    }

    /** The roles the current user holds in [tenantKey], and so may give an application (`CreateApplication` enforces it). */
    private fun grantableRoles(tenantKey: TenantKey): Set<TenantRole> = SecurityContext.current().rolesFor(tenantKey)

    private fun grantableRoleOptions(tenantKey: TenantKey): List<RoleOption> {
        val grantable = grantableRoles(tenantKey).map { it.name }.toSet()
        return ROLE_OPTIONS.filter { it.value in grantable }
    }

    companion object {
        // Order mirrors the privilege ladder. Viewer is pre-checked so the narrowest scope is the
        // default; administration is offered but never pre-selected.
        val ROLE_OPTIONS = listOf(
            RoleOption(TenantRole.CONTENT_VIEWER.name, "Viewer", "Read-only access to templates, themes, stencils, reference data and catalogs. Not generated documents.", defaultChecked = true),
            RoleOption(TenantRole.CONTENT_AUTHOR.name, "Author", "Create and edit templates, themes, stencils, and reference data.", defaultChecked = false),
            RoleOption(TenantRole.DOCUMENT_GENERATOR.name, "Generator", "Generate documents, and read the documents generated.", defaultChecked = false),
            RoleOption(TenantRole.DOCUMENT_READER.name, "Document reader", "Read generated documents without generating them.", defaultChecked = false),
            RoleOption(TenantRole.CONTENT_PUBLISHER.name, "Publisher", "Release catalogs, deploy releases to environments, and edit quality profiles.", defaultChecked = false),
            RoleOption(
                TenantRole.TENANT_ADMINISTRATOR.name,
                "Administrator",
                "Tenant settings, integrations, applications and API keys, support requests, catalogs, logs and the audit trail.",
                defaultChecked = false,
            ),
        )

        /** Role enum name → short human label. */
        val ROLE_LABELS: Map<String, String> = ROLE_OPTIONS.associate { it.value to it.label }
    }
}
