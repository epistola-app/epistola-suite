package app.epistola.suite.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
//        http
//            .authorizeHttpRequests { authorize ->
//                authorize
//                    .requestMatchers("/templates/**", "/css/**", "/js/**", "/actuator/health").permitAll()
//                    .anyRequest().authenticated()
//            }
//            . formLogin { }

        return http.build()
    }
}
