package app.epistola.suite.config

import app.epistola.suite.handlers.ShellModelInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig(private val shellModelInterceptor: ShellModelInterceptor) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(shellModelInterceptor)
            .addPathPatterns("/tenants/**")
    }
}
