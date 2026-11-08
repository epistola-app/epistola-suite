// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant

class SupportClientKeysTest {
    private val key = SupportClientKeys.generate()
    private val now = Instant.parse("2026-11-05T14:00:00Z")
    private val tokenEndpoint = URI("https://support.epistola.app/oauth2/token")

    @Test
    fun `a generated key is P-256 for ES256, with its thumbprint as kid`() {
        assertThat(key.curve).isEqualTo(Curve.P_256)
        assertThat(key.algorithm).isEqualTo(JWSAlgorithm.ES256)
        assertThat(key.keyID).isEqualTo(key.computeThumbprint().toString())
        assertThat(SupportClientKeys.generate().keyID).isNotEqualTo(key.keyID)
    }

    @Test
    fun `the registered JWK carries no private part`() {
        val jwk = SupportClientKeys.publicJwk(key)

        assertThat(jwk).doesNotContainKey("d")
        assertThat(jwk).containsEntry("kty", "EC").containsEntry("crv", "P-256").containsEntry("kid", key.keyID)
            .containsEntry("use", "sig").containsEntry("alg", "ES256")
        assertThat(ECKey.parse(jwk).isPrivate).isFalse()
    }

    @Test
    fun `an assertion is signed with the key and carries the protocol's claims`() {
        val jwt = SignedJWT.parse(SupportClientKeys.assertion(key, "sc_123", tokenEndpoint, now, "jti-1"))

        assertThat(jwt.header.algorithm).isEqualTo(JWSAlgorithm.ES256)
        assertThat(jwt.header.keyID).isEqualTo(key.keyID)
        assertThat(jwt.verify(ECDSAVerifier(key.toPublicJWK()))).isTrue()
        val claims = jwt.jwtClaimsSet
        assertThat(claims.issuer).isEqualTo("sc_123")
        assertThat(claims.subject).isEqualTo("sc_123")
        assertThat(claims.audience).containsExactly(tokenEndpoint.toString())
        assertThat(claims.jwtid).isEqualTo("jti-1")
        assertThat(claims.issueTime.toInstant()).isEqualTo(now)
        assertThat(claims.expirationTime.toInstant()).isEqualTo(now.plusSeconds(120))
    }

    @Test
    fun `an assertion from another key does not verify`() {
        val jwt = SignedJWT.parse(SupportClientKeys.assertion(SupportClientKeys.generate(), "sc_123", tokenEndpoint, now, "jti-2"))

        assertThat(jwt.verify(ECDSAVerifier(key.toPublicJWK()))).isFalse()
    }

    @Test
    fun `a stored key reads back as the same key`() {
        val parsed = ECKey.parse(key.toJSONString())

        assertThat(parsed.isPrivate).isTrue()
        assertThat(parsed.keyID).isEqualTo(key.keyID)
        val jwt = SignedJWT.parse(SupportClientKeys.assertion(parsed, "sc_123", tokenEndpoint, now, "jti-3"))
        assertThat(jwt.verify(ECDSAVerifier(key.toPublicJWK()))).isTrue()
    }
}
