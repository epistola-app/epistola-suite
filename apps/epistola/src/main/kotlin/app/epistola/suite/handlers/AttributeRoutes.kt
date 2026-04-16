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
            GET("/{catalogId}/{attributeId}/edit", handler::editForm)
            PATCH("/{catalogId}/{attributeId}", handler::update)
            POST("/{catalogId}/{attributeId}/delete", handler::delete)
        }
    }
}
