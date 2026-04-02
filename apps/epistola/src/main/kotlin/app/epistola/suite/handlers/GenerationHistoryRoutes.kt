package app.epistola.suite.handlers

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class GenerationHistoryRoutes(private val handler: GenerationHistoryHandler) {
    @Bean
    fun generationHistoryRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/generation-history".nest {
            GET("", handler::dashboard)
            GET("/search", handler::searchJobs)
        }
    }
}
