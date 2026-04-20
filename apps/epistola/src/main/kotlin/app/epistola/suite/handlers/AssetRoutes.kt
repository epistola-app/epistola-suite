package app.epistola.suite.assets

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class AssetRoutes(private val handler: AssetHandler) {
    @Bean
    fun assetRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/assets".nest {
            GET("", handler::list)
            GET("/new", handler::newForm)
            POST("", handler::upload)
            GET("/search", handler::search)

            GET("/{catalogId}/{assetId}/content", handler::content)
            POST("/{catalogId}/{assetId}/delete", handler::delete)
        }
    }
}
