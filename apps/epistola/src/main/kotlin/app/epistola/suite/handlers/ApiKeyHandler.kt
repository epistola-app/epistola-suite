package app.epistola.suite.handlers

import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.apikeys.commands.RevokeApiKey
import app.epistola.suite.apikeys.queries.ListApiKeys
import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SecurityContext
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
        val apiKeys = ListApiKeys(tenantId = tenantId.key).query().filter { it.enabled }
        return ServerResponse.ok().page("api-keys/list") {
            "pageTitle" to "API Keys - Epistola"
            "tenantId" to tenantId.key
            "apiKeys" to apiKeys
        }
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        // HTMX requests load the dialog into #dialog-host; a direct GET still
        // renders the full-page fallback.
        return request.htmx {
            fragment("api-keys/new", "createDialog") {
                "tenantId" to tenantId.key
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/api-keys") }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        val form = request.form {
            field("name") {
                required()
                maxLength(100)
            }
            field("expiresAt") {}
        }

        val expiresAtError = parseExpiresAt(form["expiresAt"]).errorOrNull()
        val errors = form.errors.toMutableMap()
        if (expiresAtError != null) errors["expiresAt"] = expiresAtError

        if (errors.isNotEmpty()) {
            // Re-render the form: the lone `createForm` fragment swaps itself in
            // place over HTMX (dialog stays open); a full page for non-HTMX.
            return request.htmx {
                fragment("api-keys/new", "createForm") {
                    "tenantId" to tenantId.key
                    "formData" to form.formData
                    "errors" to errors
                }
                onNonHtmx { redirect("/tenants/${tenantId.key}/api-keys") }
            }
        }

        val expiresAt = (parseExpiresAt(form["expiresAt"]) as ParsedExpiry.Ok).value
        val principal = SecurityContext.currentOrNull()

        val result = CreateApiKey(
            tenantId = tenantId.key,
            name = form["name"],
            expiresAt = expiresAt,
            createdBy = principal?.userId,
        ).execute()

        // Success reveals the secret once. Over HTMX the reveal swaps over the
        // form inside the open dialog; non-HTMX renders the created page.
        return request.htmx {
            fragment("api-keys/new", "createdReveal") {
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
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/api-keys") }
        }
    }

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
}
