package app.epistola.suite.loadtest

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class LoadTestRoutes(private val handler: LoadTestHandler) {
    @Bean
    fun loadTestRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/load-tests".nest {
            GET("", handler::list)
            GET("/new", handler::newForm)
            POST("", handler::start)

            GET("/{runId}", handler::detail)
            GET("/{runId}/metrics", handler::metrics)
            GET("/{runId}/requests", handler::requests)
            POST("/{runId}/cancel", handler::cancel)
        }
    }
}
