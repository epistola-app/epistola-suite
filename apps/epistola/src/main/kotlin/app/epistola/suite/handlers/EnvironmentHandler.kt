package app.epistola.suite.environments

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.paging.PagedResult
import app.epistola.suite.common.paging.SortDirection
import app.epistola.suite.common.paging.SortSpec
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeleteEnvironment
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.environmentId
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.table.Column
import app.epistola.suite.htmx.table.ListQuery
import app.epistola.suite.htmx.table.ListViewState
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class EnvironmentHandler {
    private val sortableColumns = setOf("name", "id", "created")
    private val pageSizeOptions = listOf(10, 25, 50)
    private val defaultSort = SortSpec("name", SortDirection.ASC)

    // Name flexes (width = null); the rest are fixed so the layout stays predictable.
    private val columns = listOf(
        Column("Name", "name"),
        Column("ID", "id", width = "14rem"),
        Column("Created", "created", width = "12rem"),
        Column("", width = "5rem"),
    )

    /**
     * Unified list endpoint: full page on a normal request, the data-table fragment on
     * an HTMX request. Search/sort/paging state is read from (and pushed back to) the
     * query string, so the view is bookmarkable and survives a refresh.
     */
    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()
        val table = loadTable(request, tenantId)

        val model: ModelBuilder.() -> Unit = {
            "pageTitle" to "Environments - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "columns" to columns
            "query" to table.query
            "paged" to table.paged
            "pageSizeOptions" to pageSizeOptions
        }

        return request.htmx {
            fragment("environments/list", "data-table-fragment", model)
            pushUrl(table.query.canonicalUrl())
            onNonHtmx { page("environments/list", model) }
        }
    }

    private data class EnvTable(val query: ListQuery, val paged: PagedResult<Environment>)

    /** Parse the list state and run the paged query — shared by list and the post-delete refresh. */
    private fun loadTable(request: ServerRequest, tenantId: TenantId): EnvTable {
        val basePath = "/tenants/${tenantId.key}/environments"
        val state = ListViewState.from(
            request = request,
            basePath = basePath,
            sortable = sortableColumns,
            defaultSort = defaultSort,
            pageSizes = pageSizeOptions,
            filterNames = listOf("q"),
        )
        val paged = ListEnvironments(
            tenantId = tenantId,
            searchTerm = state.filter("q"),
            sort = state.sort,
            page = state.pageRequest,
        ).query()
        return EnvTable(state.toQuery(paged.page), paged)
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        return ServerResponse.ok().page("environments/new") {
            "pageTitle" to "New Environment - Epistola"
            "tenantId" to tenantId.key
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        val form = request.form {
            field("slug") {
                required()
                asEnvironmentId()
            }
            field("name") {
                required()
                maxLength(100)
            }
        }

        if (form.hasErrors()) {
            return ServerResponse.ok().page("environments/new") {
                "pageTitle" to "New Environment - Epistola"
                "tenantId" to tenantId.key
                "formData" to form.formData
                "errors" to form.errors
            }
        }

        val environmentKey = form.getEnvironmentId("slug")!!
        val name = form["name"]

        val result = form.executeOrFormError {
            CreateEnvironment(
                id = EnvironmentId(environmentKey, tenantId),
                name = name,
            ).execute()
        }

        if (result.hasErrors()) {
            return ServerResponse.ok().page("environments/new") {
                "pageTitle" to "New Environment - Epistola"
                "tenantId" to tenantId.key
                "formData" to result.formData
                "errors" to result.errors
            }
        }

        return ServerResponse.status(303)
            .header("Location", "/tenants/${tenantId.key}/environments")
            .build()
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val environmentId = request.environmentId(tenantId)
            ?: return ServerResponse.badRequest().build()

        try {
            DeleteEnvironment(id = environmentId).execute()
        } catch (e: EnvironmentInUseException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to e.message))
        }

        // Re-render the whole table (rows + footer) after delete, since the count and
        // pagination may change. The delete POST carries no list state, so this resets to
        // the default view — acceptable for a row removal.
        val table = loadTable(request, tenantId)
        return request.htmx {
            fragment("environments/list", "data-table-fragment") {
                "tenantId" to tenantId.key
                "columns" to columns
                "query" to table.query
                "paged" to table.paged
                "pageSizeOptions" to pageSizeOptions
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/environments") }
        }
    }
}
