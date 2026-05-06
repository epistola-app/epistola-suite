package app.epistola.suite.handlers

import app.epistola.suite.generation.collect.queries.ListConsumerStatus
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.query
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Read-only operations view of every API-key consumer for a tenant + the
 * nodes currently polling under each key. The page auto-refreshes via HTMX
 * (10s tick) by hitting [refresh], which returns just the inner fragment.
 */
@Component
class ConsumerStatusHandler {

    fun dashboard(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val report = ListConsumerStatus(tenantId.key).query()

        return ServerResponse.ok().page("consumers/dashboard") {
            "pageTitle" to "Consumers - Epistola"
            "tenantId" to tenantId.key
            "tenantName" to report.tenantName
            "activeNavSection" to "consumers"
            "report" to report
        }
    }

    fun refresh(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val report = ListConsumerStatus(tenantId.key).query()

        return request.htmx {
            fragment("consumers/dashboard", "results") {
                "tenantId" to tenantId.key
                "report" to report
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/consumers") }
        }
    }
}
