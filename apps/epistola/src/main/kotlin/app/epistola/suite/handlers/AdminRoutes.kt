package app.epistola.suite.handlers

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class AdminRoutes(private val handler: AdminHandler) {
    @Bean
    fun adminRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/admin".nest {
            GET("/data-management", handler::dataManagement)
        }
    }
}
