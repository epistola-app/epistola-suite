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

            "/{stencilId}".nest {
                GET("", handler::detail)
                PATCH("", handler::update)
                POST("/delete", handler::delete)
                PUT("/draft", handler::updateDraft)

                "/versions".nest {
                    GET("", handler::listVersions)
                    POST("", handler::createVersion)
                    GET("/{versionId}", handler::getVersion)
                    POST("/{versionId}/publish", handler::publishVersion)
                    POST("/{versionId}/archive", handler::archiveVersion)
                }
            }
        }
    }
}
