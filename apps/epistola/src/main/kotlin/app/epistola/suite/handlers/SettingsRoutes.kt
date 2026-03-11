package app.epistola.suite.handlers

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class SettingsRoutes(private val handler: SettingsHandler) {
    @Bean
    fun settingsRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/settings".nest {
            GET("/feedback-sync", handler::feedbackSync)
            POST("/feedback-sync", handler::saveFeedbackSync)
        }
    }
}
