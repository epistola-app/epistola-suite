package app.epistola.suite.api.security

import app.epistola.suite.security.EpistolaPrincipal
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority

/**
 * Spring Security authentication token for API key-authenticated requests.
 *
 * The principal is an [EpistolaPrincipal] representing the API key as a
 * Non-Personal Account (NPA) — the key's own UUID serves as the userId.
 */
class ApiKeyAuthenticationToken(
    private val principal: EpistolaPrincipal,
) : AbstractAuthenticationToken(listOf(SimpleGrantedAuthority("ROLE_API_KEY"))) {

    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): EpistolaPrincipal = principal
}
