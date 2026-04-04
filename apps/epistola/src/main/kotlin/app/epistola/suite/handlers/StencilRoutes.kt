package app.epistola.suite.handlers

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class StencilRoutes(private val handler: StencilHandler) {
    @Bean
    fun stencilRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/stencils".nest {
            GET("", handler::list)
            GET("/search", handler::search)
            GET("/new", handler::newForm)
            POST("", handler::create)
            POST("/publish-from-editor", handler::publishFromEditor)

            GET("/{stencilId}", handler::detail)
            PATCH("/{stencilId}", handler::update)
            POST("/{stencilId}/delete", handler::delete)
            POST("/{stencilId}/start-editing", handler::startEditing)
            POST("/{stencilId}/publish-draft", handler::publishDraftFromEditor)
            POST("/{stencilId}/update-from-editor", handler::updateFromEditor)
            POST("/{stencilId}/versions", handler::createVersion)
            GET("/{stencilId}/versions/{versionId}", handler::getVersion)
            POST("/{stencilId}/versions/{versionId}/publish", handler::publishVersion)
            POST("/{stencilId}/versions/{versionId}/archive", handler::archiveVersion)
        }
    }
}
