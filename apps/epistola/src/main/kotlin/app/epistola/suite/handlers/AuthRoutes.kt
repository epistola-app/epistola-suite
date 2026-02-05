package app.epistola.suite.handlers

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class AuthRoutes(
    private val loginHandler: LoginHandler,
) {
    @Bean
    fun loginRoutes(): RouterFunction<ServerResponse> = router {
        GET("/login", loginHandler::loginPage)
        GET("/login-popup-success", loginHandler::loginPopupSuccess)
    }
}
