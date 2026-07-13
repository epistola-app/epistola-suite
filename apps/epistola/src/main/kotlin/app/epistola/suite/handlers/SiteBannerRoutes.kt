package app.epistola.suite.handlers

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class SiteBannerRoutes(private val handler: SiteBannerHandler) {
    @Bean
    fun siteBannerRouterFunction(): RouterFunction<ServerResponse> = router {
        "/platform/banner".nest {
            GET("", handler::edit)
            POST("", handler::save)
        }
    }
}
