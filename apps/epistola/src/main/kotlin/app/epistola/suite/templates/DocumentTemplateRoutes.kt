package app.epistola.suite.templates

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class DocumentTemplateRoutes(private val handler: DocumentTemplateHandler) {
    @Bean
    fun templateRoutes(): RouterFunction<ServerResponse> = router {
        "/templates".nest {
            GET("", handler::list)
            POST("", handler::create)
        }
    }
}
