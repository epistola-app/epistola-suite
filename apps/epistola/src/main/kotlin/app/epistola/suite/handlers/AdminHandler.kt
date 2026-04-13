package app.epistola.suite.handlers

import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.security.SecurityContext
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class AdminHandler {

    fun dataManagement(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requireManager(tenantId.key)

        return ServerResponse.ok().page("admin/data-management") {
            "pageTitle" to "Data Management - Epistola"
            "tenantId" to tenantId.key
            "activeNavSection" to "admin"
        }
    }

    private fun requireManager(tenantKey: app.epistola.suite.common.ids.TenantKey) {
        val principal = SecurityContext.current()
        if (!principal.isManager(tenantKey)) {
            throw org.springframework.security.access.AccessDeniedException("Manager access required")
        }
    }
}
