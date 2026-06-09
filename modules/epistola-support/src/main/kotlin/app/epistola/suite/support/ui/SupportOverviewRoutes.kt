package app.epistola.suite.support.ui

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class SupportOverviewRoutes(
    private val handler: SupportOverviewHandler,
) {
    @Bean
    fun supportOverviewRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/support".nest {
            GET("", handler::overview)
            POST("/entitlements/refresh", handler::refreshEntitlements)
        }
    }
}
