package app.epistola.suite.fonts

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class FontRoutes(private val handler: FontHandler) {
    @Bean
    fun fontRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/fonts".nest {
            GET("", handler::list)
            GET("/new", handler::newForm)
            POST("", handler::upload)
            GET("/search", handler::search)

            GET("/{catalogId}/{slug}/{weight}/{italic}/content", handler::content)
            POST("/{catalogId}/{slug}/delete", handler::delete)
        }
    }
}
