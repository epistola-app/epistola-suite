package app.epistola.suite.environments

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class EnvironmentRoutes(private val handler: EnvironmentHandler) {
    @Bean
    fun environmentRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/environments".nest {
            GET("", handler::list)
            GET("/search", handler::search)
            GET("/new", handler::newForm)
            POST("", handler::create)
            POST("/{environmentId}/delete", handler::delete)
        }
    }
}
