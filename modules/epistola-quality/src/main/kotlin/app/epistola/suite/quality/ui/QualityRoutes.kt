package app.epistola.suite.quality.ui

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

/**
 * UI routes for the quality report. Under `/tenants/{tenantId}` like every other UI surface, and
 * deliberately **not** under `/api` — the REST API is for external systems only, and this is the
 * server-rendered UI (see `UiRestApiSeparationTest`).
 */
@Configuration
class QualityRoutes(private val handler: QualityHandler) {
    @Bean
    fun qualityRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/quality".nest {
            GET("", handler::list)
            GET("/search", handler::search)
            GET("/manual-form", handler::manualForm)
            GET("/variants", handler::variantOptions)
            POST("", handler::createManual)
            GET("/{findingId}", handler::detail)
            POST("/{findingId}/ignore", handler::ignore)
            POST("/{findingId}/unignore", handler::unignore)
            POST("/{findingId}/resolve", handler::resolve)
            POST("/{findingId}/comments", handler::addComment)
        }
    }
}
