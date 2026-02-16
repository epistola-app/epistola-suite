package app.epistola.suite.attributes

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class AttributeRoutes(private val handler: AttributeHandler) {
    @Bean
    fun attributeRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/attributes".nest {
            GET("", handler::list)
            GET("/new", handler::newForm)
            POST("", handler::create)
            GET("/{attributeId}/edit", handler::editForm)
            PATCH("/{attributeId}", handler::update)
            POST("/{attributeId}/delete", handler::delete)
        }
    }
}
