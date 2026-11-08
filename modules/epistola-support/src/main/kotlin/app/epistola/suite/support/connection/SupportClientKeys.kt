// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * The installation's key and the client assertions it signs (RFC 7523, `docs/support-protocol.md`
 * "Tokens"). The private key never leaves the installation; only [publicJwk] is registered.
 */
object SupportClientKeys {
    /** How long an assertion is valid. The service accepts at most five minutes. */
    val ASSERTION_LIFETIME: Duration = Duration.ofSeconds(120)

    /**
     * The clock skew the service allows an assertion: `iat` at most this far ahead of its clock, and
     * not used past `exp` plus this.
     */
    val CLOCK_SKEW_TOLERANCE: Duration = Duration.ofSeconds(60)

    /** A fresh EC P-256 key for ES256, with its RFC 7638 thumbprint as `kid`. */
    fun generate(): ECKey {
        val key = ECKeyGenerator(Curve.P_256).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256).generate()
        return ECKey.Builder(key).keyIDFromThumbprint().build()
    }

    /** The public half as registered: no private parts. */
    fun publicJwk(key: ECKey): Map<String, Any> = key.toPublicJWK().toJSONObject()

    /**
     * A signed client assertion: `alg` ES256 with the registered `kid`; `iss` = `sub` = [clientId],
     * `aud` = [audience] (the token endpoint), the given [jti], `iat` = [now] and `exp` 120 seconds
     * later.
     */
    fun assertion(key: ECKey, clientId: String, audience: URI, now: Instant, jti: String): String {
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).type(JOSEObjectType.JWT).build()
        val claims = JWTClaimsSet.Builder()
            .issuer(clientId)
            .subject(clientId)
            .audience(audience.toString())
            .jwtID(jti)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(ASSERTION_LIFETIME)))
            .build()
        return SignedJWT(header, claims).apply { sign(ECDSASigner(key)) }.serialize()
    }
}
