// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.embedding.EmbeddingProperties
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
    tableName = "web_session",
)
class SessionConfig(
    private val embeddingProperties: EmbeddingProperties,
) {

    /**
     * ADR 0015: SameSite=Lax is dropped by browsers on every request an
     * embedded (cross-origin) iframe's own JS makes back to its own origin, so
     * the session cookie never reaches the server when embedding is on —
     * without this, every embedded page renders a login-wall. Scoped strictly
     * to epistola.embedding.enabled=true (demo profile only); every other
     * deployment keeps today's SameSite=Lax and untouched Secure behavior
     * (Spring Session's own default: mirrors HttpServletRequest.isSecure(), so
     * a plain setUseSecureCookie(false) here would wrongly strip Secure from an
     * HTTPS deployment that never asked for embedding). Secure is mandatory
     * alongside SameSite=None or browsers reject the cookie outright, so it is
     * only ever forced — never forced off.
     */
    @Bean
    fun cookieSerializer(): DefaultCookieSerializer = DefaultCookieSerializer().apply {
        setCookieName("sid")
        setCookiePath("/")
        setUseHttpOnlyCookie(true)
        if (embeddingProperties.cookiesNeedSameSiteNone) {
            setSameSite("None")
            setUseSecureCookie(true)
        } else {
            setSameSite("Lax")
        }
    }
}
