package app.epistola.suite.handlers

import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.apikeys.commands.RevokeApiKey
import app.epistola.suite.apikeys.queries.ListApiKeys
import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.htmx.FormData
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.security.requirePermission
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

@Component
class ApiKeyHandler {

    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        return ServerResponse.ok().page("api-keys/list") {
            apiKeyPageModel(tenantId)
        }
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_USERS)
        return request.htmx {
            // In-app trigger (hx-get → #dialog-mount): just the dialog fragment.
            fragment("api-keys/new", "dialog") {
                "tenantId" to tenantId.key
                "roleOptions" to ROLE_OPTIONS
            }
            // Direct navigation / boost: the host list page with the dialog
            // embedded in its mount (openDialog=true), opened on load by the JS.
            onNonHtmx {
                page("api-keys/list") {
                    apiKeyPageModel(tenantId)
                    "openDialog" to true
                    "roleOptions" to ROLE_OPTIONS
                }
            }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_USERS)

        val form = request.form {
            field("name") {
                required()
                maxLength(100)
            }
            field("expiresAt") {}
        }

        val selectedRoleNames = request.params()["roles"].orEmpty()
        val roles = selectedRoleNames.mapNotNull { name -> runCatching { TenantRole.valueOf(name) }.getOrNull() }.toSet()

        val expiresAtError = parseExpiresAt(form["expiresAt"]).errorOrNull()
        val errors = form.errors.toMutableMap()
        if (expiresAtError != null) errors["expiresAt"] = expiresAtError
        if (roles.isEmpty()) errors["roles"] = "Select at least one role"

        if (errors.isNotEmpty()) {
            val formData = FormData(form.formData, errors)
            return request.htmx {
                dialogFieldErrors(
                    template = "api-keys/new",
                    fragmentName = "api-key-form",
                    formTarget = "#create-api-key-form",
                    formData = formData,
                ) {
                    "tenantId" to tenantId.key
                    "roleOptions" to ROLE_OPTIONS
                    "selectedRoles" to selectedRoleNames
                }
                onNonHtmx {
                    page(422, "api-keys/list") {
                        apiKeyPageModel(tenantId)
                        "openDialog" to true
                        "roleOptions" to ROLE_OPTIONS
                        "selectedRoles" to selectedRoleNames
                        "formData" to formData.formData
                        "errors" to errors
                    }
                }
            }
        }

        val expiresAt = (parseExpiresAt(form["expiresAt"]) as ParsedExpiry.Ok).value
        val principal = SecurityContext.currentOrNull()

        val result = CreateApiKey(
            tenantId = tenantId.key,
            name = form["name"],
            roles = roles,
            expiresAt = expiresAt,
            createdBy = principal?.userId,
        ).execute()

        // Success is a REVEAL, not a redirect: the one-time plaintext key panel is
        // swapped into the dialog in place of the form and the dialog STAYS OPEN
        // (dialogReveal omits closeDialog) so the user can copy the key before
        // dismissing it. "Done" is a link back to the list.
        return request.htmx {
            dialogReveal(
                template = "api-keys/created",
                fragmentName = "created-reveal",
                revealTarget = "#create-api-key-form",
            ) {
                "tenantId" to tenantId.key
                "plaintextKey" to result.plaintextKey
                "apiKey" to result.apiKey
            }
            onNonHtmx {
                page("api-keys/created") {
                    "pageTitle" to "API key created - Epistola"
                    "tenantId" to tenantId.key
                    "plaintextKey" to result.plaintextKey
                    "apiKey" to result.apiKey
                }
            }
        }
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val apiKeyKey = runCatching { ApiKeyKey.of(request.pathVariable("apiKeyId")) }
            .getOrNull() ?: return ServerResponse.badRequest().build()

        val principal = SecurityContext.currentOrNull()
        RevokeApiKey(
            tenantId = tenantId.key,
            id = apiKeyKey,
            revokedBy = principal?.userId,
        ).execute()

        val apiKeys = ListApiKeys(tenantId = tenantId.key).query().filter { it.enabled }
        return request.htmx {
            fragment("api-keys/list", "rows") {
                "tenantId" to tenantId.key
                "apiKeys" to apiKeys
                "roleLabels" to ROLE_LABELS
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/api-keys") }
        }
    }

    /**
     * The full-page list model, shared by [list] and the newForm / create
     * non-HTMX branches so the list renders behind the embedded create dialog.
     */
    private fun ModelBuilder.apiKeyPageModel(tenantId: TenantId) {
        "pageTitle" to "API Keys - Epistola"
        "tenantId" to tenantId.key
        "apiKeys" to ListApiKeys(tenantId = tenantId.key).query().filter { it.enabled }
        "roleLabels" to ROLE_LABELS
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
            val date = LocalDate.parse(raw)
            ParsedExpiry.Ok(date.atStartOfDay(ZoneOffset.UTC).toInstant())
        } catch (_: DateTimeParseException) {
            ParsedExpiry.Err("Expires must be a valid date (YYYY-MM-DD)")
        }
    }

    companion object {
        // Order mirrors the privilege ladder. Viewer is pre-checked so the narrowest scope is the
        // default; administration (settings/users/catalogs/backups/destructive restore) is offered
        // but never pre-selected, keeping keys least-privilege unless deliberately escalated.
        val ROLE_OPTIONS = listOf(
            RoleOption(TenantRole.CONTENT_VIEWER.name, "Viewer", "Read-only access across the tenant.", defaultChecked = true),
            RoleOption(TenantRole.CONTENT_AUTHOR.name, "Author", "Create and edit templates, themes, stencils, and reference data.", defaultChecked = false),
            RoleOption(TenantRole.DOCUMENT_GENERATOR.name, "Generator", "Generate documents.", defaultChecked = false),
            RoleOption(TenantRole.CONTENT_PUBLISHER.name, "Publisher", "Publish and archive template and stencil versions.", defaultChecked = false),
            RoleOption(
                TenantRole.TENANT_ADMINISTRATOR.name,
                "Administrator",
                "Tenant settings, users, catalogs, diagnostics, backups, and destructive restore.",
                defaultChecked = false,
            ),
        )

        /** Role enum name → short human label, for rendering a key's scope on the list page. */
        val ROLE_LABELS: Map<String, String> = ROLE_OPTIONS.associate { it.value to it.label }
    }
}
