package app.epistola.suite.themes

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class ThemeRoutes(private val handler: ThemeHandler) {
    @Bean
    fun themeRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}".nest {
            POST("/default-theme", handler::setDefault)

            "/themes".nest {
                GET("", handler::list)
                GET("/search", handler::search)
                POST("", handler::create)

                GET("/{themeId}", handler::detail)
                PATCH("/{themeId}", handler::update)
                POST("/{themeId}/delete", handler::delete)
            }
        }
    }
}
