package app.epistola.suite.handlers

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class FeedbackRoutes(private val handler: FeedbackHandler) {
    @Bean
    fun feedbackRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/feedback".nest {
            GET("", handler::list)
            GET("/search", handler::search)
            GET("/submit-form", handler::submitForm)
            POST("", handler::create)
            GET("/{feedbackId}", handler::detail)
            POST("/{feedbackId}/status", handler::updateStatus)
            POST("/{feedbackId}/comments", handler::addComment)
        }
    }
}
