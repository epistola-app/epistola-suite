package app.epistola.suite.handlers

import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.apikeys.commands.RevokeApiKey
import app.epistola.suite.apikeys.queries.ListApiKeys
import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.paging.SortDirection
import app.epistola.suite.common.paging.SortSpec
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.table.Column
import app.epistola.suite.htmx.table.ListViewState
import app.epistola.suite.htmx.table.PAGE_SIZES
import app.epistola.suite.htmx.table.dataTableResponse
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

@Component
class ApiKeyHandler {

    private val sortableColumns = setOf("name", "created", "lastUsed", "expires")
    private val pageSizeOptions = PAGE_SIZES
    private val defaultSort = SortSpec("created", SortDirection.DESC)

    // Name flexes (width = null); the rest are fixed. See ADR 0007.
    private val columns = listOf(
        Column("Name", "name"),
        Column("Prefix", width = "8rem"),
        Column("Scope", width = "12rem"),
        Column("Created by", width = "10rem"),
        Column("Created", "created", width = "9rem"),
        Column("Last used", "lastUsed", width = "9rem"),
        Column("Expires", "expires", width = "8rem"),
        Column("", width = "4rem"),
    )

    /**
     * Unified list endpoint: full page on a normal request, the data-table fragment on
     * an HTMX request. Search/sort/paging state is read from (and pushed back to) the query
     * string, so the view is bookmarkable and survives a refresh.
     */
    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val (pushUrl, model) = loadTableModel(request, tenantId)
        return request.dataTableResponse("api-keys/list", "API Keys - Epistola", pushUrl, model)
    }

    /** Parse the list state and run the paged query — shared by list and the post-delete refresh. */
    private fun loadTableModel(request: ServerRequest, tenantId: TenantId): Pair<String, ModelBuilder.() -> Unit> {
        val basePath = "/tenants/${tenantId.key}/api-keys"
        val state = ListViewState.from(
            request = request,
            basePath = basePath,
            sortable = sortableColumns,
            defaultSort = defaultSort,
            pageSizes = pageSizeOptions,
            filterNames = listOf("q"),
        )
        val paged = ListApiKeys(
            tenantId = tenantId.key,
            searchTerm = state.filter("q"),
            sort = state.sort,
            page = state.pageRequest,
        ).query()
        val query = state.toQuery(paged.page)

        val model: ModelBuilder.() -> Unit = {
            "tenantId" to tenantId.key
            "columns" to columns
            "query" to query
            "paged" to paged
            "pageSizeOptions" to pageSizeOptions
            "roleLabels" to ROLE_LABELS
        }
        return query.canonicalUrl() to model
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        return ServerResponse.ok().page("api-keys/new") {
            "pageTitle" to "New API key - Epistola"
            "tenantId" to tenantId.key
            "roleOptions" to ROLE_OPTIONS
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

        val selectedRoleNames = request.params()["roles"].orEmpty()
        val roles = selectedRoleNames.mapNotNull { name -> runCatching { TenantRole.valueOf(name) }.getOrNull() }.toSet()

        val expiresAtError = parseExpiresAt(form["expiresAt"]).errorOrNull()
        val errors = form.errors.toMutableMap()
        if (expiresAtError != null) errors["expiresAt"] = expiresAtError
        if (roles.isEmpty()) errors["roles"] = "Select at least one role"

        if (errors.isNotEmpty()) {
            return ServerResponse.ok().page("api-keys/new") {
                "pageTitle" to "New API key - Epistola"
                "tenantId" to tenantId.key
                "formData" to form.formData
                "errors" to errors
                "roleOptions" to ROLE_OPTIONS
                "selectedRoles" to selectedRoleNames
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

        return ServerResponse.ok().page("api-keys/created") {
            "pageTitle" to "API key created - Epistola"
            "tenantId" to tenantId.key
            "plaintextKey" to result.plaintextKey
            "apiKey" to result.apiKey
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

        // Re-render the whole table after revoke. The POST carries no list state, so this
        // resets to the default view — acceptable for a row removal.
        val (_, model) = loadTableModel(request, tenantId)
        return request.htmx {
            fragment("api-keys/list", "data-table-fragment", model)
            onNonHtmx { redirect("/tenants/${tenantId.key}/api-keys") }
        }
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
