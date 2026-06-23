package app.epistola.suite.audit.ui

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class AuditRoutes(private val handler: AuditHandler) {
    @Bean
    fun auditRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/audit".nest {
            GET("", handler::list)
            GET("/search", handler::search)
            GET("/older", handler::older)
            GET("/newer", handler::newer)
        }
    }
}
