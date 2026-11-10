// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.helpers.NOPLogger
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant

/**
 * A realm's issuer is shared by every application in it: with an audience configured, a bearer
 * token is accepted only when its `aud` names Epistola. Without one, only the issuer is checked, as
 * before, and the boot warns.
 */
class JwtAudiencePolicyTest {

    private val issuer = "https://sso.example.com/realms/shared"

    private fun token(vararg audiences: String): Jwt = Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .issuer(issuer)
        .subject("someone")
        .issuedAt(Instant.now().minusSeconds(10))
        .expiresAt(Instant.now().plusSeconds(300))
        .apply { if (audiences.isNotEmpty()) audience(audiences.toList()) }
        .build()

    private fun accepted(jwt: Jwt, audiences: List<String>? = listOf("epistola-suite")) = !JwtAudiencePolicy.validator(issuer, audiences).validate(jwt).hasErrors()

    @Test
    fun `a token issued for Epistola is accepted`() {
        assertThat(accepted(token("epistola-suite"))).isTrue()
        assertThat(accepted(token("account", "epistola-suite"))).isTrue()
    }

    @Test
    fun `a token of the same realm issued for another application is refused`() {
        assertThat(accepted(token("intranet-portal"))).isFalse()
    }

    @Test
    fun `a token naming no audience is refused`() {
        assertThat(accepted(token())).isFalse()
    }

    @Test
    fun `any of several audiences will do`() {
        assertThat(accepted(token("epistola-api"), listOf("epistola-suite", "epistola-api"))).isTrue()
    }

    @Test
    fun `without an audience configured only the issuer is checked, as before`() {
        assertThat(accepted(token("intranet-portal"), audiences = null)).isTrue()
        assertThat(accepted(token(), audiences = listOf(" "))).isTrue()
        assertThat(JwtAudiencePolicy.validator("https://other.example.com", null).validate(token("epistola-suite")).hasErrors())
            .`as`("the issuer still is").isTrue()
    }

    @Test
    fun `the boot warns when no audience is configured, and only then`() {
        assertThat(JwtAudiencePolicy.warnIfMissing(null, NOPLogger.NOP_LOGGER)).isTrue()
        assertThat(JwtAudiencePolicy.warnIfMissing(listOf(" "), NOPLogger.NOP_LOGGER)).isTrue()
        assertThat(JwtAudiencePolicy.warnIfMissing(listOf("epistola-suite"), NOPLogger.NOP_LOGGER)).isFalse()
        assertThat(JwtAudiencePolicy.MISSING_WARNING).contains(JwtAudiencePolicy.PROPERTY, "oidc.audiences")
    }
}
