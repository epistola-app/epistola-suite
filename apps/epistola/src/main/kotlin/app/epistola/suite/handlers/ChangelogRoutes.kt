package app.epistola.suite.handlers

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class ChangelogRoutes(private val handler: ChangelogHandler) {
    @Bean
    fun changelogRouterFunction(): RouterFunction<ServerResponse> = router {
        GET("/changelog", handler::view)
    }
}
