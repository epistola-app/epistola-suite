package app.epistola.suite.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession
import org.springframework.session.web.http.DefaultCookieSerializer

/**
 * Enables Spring Session JDBC for distributed session storage.
 *
 * Sessions are stored in the web_session table (created by V10 + V11 Flyway migrations).
 * This allows sessions to be shared across multiple application instances and survive
 * application restarts.
 *
 * Session timeout: 4 hours (14400 seconds), configured via maxInactiveIntervalInSeconds.
 * Cookie name: "sid", configured via cookieSerializer bean.
 */
@Configuration
@EnableJdbcHttpSession(
    tableName = "web_session"
)
class SessionConfig {

    @Bean
    fun cookieSerializer(): DefaultCookieSerializer {
        return DefaultCookieSerializer().apply {
            setCookieName("sid")
            setCookiePath("/")
            setUseHttpOnlyCookie(true)
            setSameSite("Lax")
        }
    }
}
