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
            GET("/search", handler::search)
            GET("/{catalogId}/{slug}/{variant}/content", handler::content)
        }
    }
}
