// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import org.slf4j.Logger
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtValidators

/**
 * Which application a bearer token on the REST API (`/api`) must be issued for: one of
 * `spring.security.oauth2.resourceserver.jwt.audiences`, named in the token's `aud` claim. An
 * identity provider's realm is usually shared by several applications under one issuer: checked by
 * issuer only, a token issued to any other client of the realm is accepted here with the roles it
 * carries.
 *
 * Optional, so an upgrade changes nothing for an installation that sets none: without audiences only
 * the issuer is checked, as before, and the boot logs [MISSING_WARNING].
 */
object JwtAudiencePolicy {

    const val PROPERTY: String = "spring.security.oauth2.resourceserver.jwt.audiences"

    /** Logged at boot when bearer tokens are accepted without an audience to hold them to. */
    const val MISSING_WARNING: String =
        "Bearer tokens on /api are checked for their issuer only: a token the identity provider issued to any other application " +
            "of the realm is accepted, with the roles it carries. Set $PROPERTY (Helm: oidc.audiences) to the client id Epistola's " +
            "tokens are issued for, and have the provider put it in their aud claim (docs/keycloak-setup.md)."

    /** The audiences configured, trimmed; empty when none are. */
    fun audiences(configured: List<String>?): List<String> = configured.orEmpty().map(String::trim).filter(String::isNotEmpty)

    /** Logs [MISSING_WARNING] when no audience is configured; true when it did. */
    fun warnIfMissing(configured: List<String>?, log: Logger): Boolean {
        if (audiences(configured).isNotEmpty()) return false
        log.warn("JWT audience not configured reason=\"{}\"", MISSING_WARNING)
        return true
    }

    /** The issuer's default checks (signature time, issuer), and an `aud` naming one of [audiences] when any are configured. */
    fun validator(issuerUri: String, audiences: List<String>?): OAuth2TokenValidator<Jwt> {
        val required = audiences(audiences)
        val issuer = JwtValidators.createDefaultWithIssuer(issuerUri)
        if (required.isEmpty()) return issuer
        val audience = OAuth2TokenValidator<Jwt> { jwt ->
            if (jwt.audience.orEmpty().any { it in required }) {
                OAuth2TokenValidatorResult.success()
            } else {
                OAuth2TokenValidatorResult.failure(OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The token is not issued for this application", null))
            }
        }
        return DelegatingOAuth2TokenValidator(issuer, audience)
    }
}
