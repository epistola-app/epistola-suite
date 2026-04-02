package app.epistola.suite.handlers

import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.queries.GetGenerationStats
import app.epistola.suite.documents.queries.GetTemplateUsage
import app.epistola.suite.documents.queries.ListGenerationJobs
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.query
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class GenerationHistoryHandler {

    private companion object {
        const val RECENT_JOBS_LIMIT = 20
    }

    fun dashboard(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        val stats = GetGenerationStats(tenantId.key).query()
        val templateUsage = GetTemplateUsage(tenantId.key).query()
        val recentJobs = ListGenerationJobs(tenantId.key, limit = RECENT_JOBS_LIMIT).query()

        return ServerResponse.ok().page("generation-history/dashboard") {
            "pageTitle" to "Generation History - Epistola"
            "tenantId" to tenantId.key
            "stats" to stats
            "templateUsage" to templateUsage
            "recentJobs" to recentJobs
            "statuses" to RequestStatus.entries
        }
    }

    fun searchJobs(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val statusFilter = request.queryParam("status")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { RequestStatus.valueOf(it) }.getOrNull() }

        val jobs = ListGenerationJobs(
            tenantId = tenantId.key,
            status = statusFilter,
            limit = RECENT_JOBS_LIMIT,
        ).query()

        return request.htmx {
            fragment("generation-history/dashboard", "job-rows") {
                "tenantId" to tenantId.key
                "recentJobs" to jobs
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/generation-history") }
        }
    }
}
