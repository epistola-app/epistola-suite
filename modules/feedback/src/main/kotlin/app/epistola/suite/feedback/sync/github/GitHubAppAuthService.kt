package app.epistola.suite.feedback.sync.github

import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles GitHub App authentication: JWT generation and installation token exchange.
 *
 * GitHub Apps authenticate in two steps:
 * 1. Generate a short-lived JWT signed with the app's private key (RS256)
 * 2. Exchange the JWT for an installation-scoped access token (1 hour validity)
 *
 * Tokens are cached per installation ID and refreshed at 50 minutes.
 */
class GitHubAppAuthService(
    private val properties: GitHubAppProperties,
    private val restClient: RestClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val privateKey: RSAPrivateKey = loadPrivateKey(Path.of(properties.requirePrivateKeyPath()))
    private val tokenCache = ConcurrentHashMap<Long, CachedToken>()

    fun getInstallationToken(installationId: Long): String {
        val cached = tokenCache[installationId]
        if (cached != null && !cached.isExpiringSoon()) {
            return cached.token
        }

        log.debug("Requesting new installation token for installation {}", installationId)
        val jwt = generateJwt()

        val response = restClient.post()
            .uri("/app/installations/{installationId}/access_tokens", installationId)
            .header("Authorization", "Bearer $jwt")
            .retrieve()
            .body(InstallationTokenResponse::class.java)
            ?: throw GitHubAuthException("Failed to obtain installation token for installation $installationId")

        val newToken = CachedToken(
            token = response.token,
            expiresAt = Instant.parse(response.expiresAt),
        )
        tokenCache[installationId] = newToken
        log.debug("Obtained installation token for installation {}, expires at {}", installationId, response.expiresAt)

        return newToken.token
    }

    private fun generateJwt(): String {
        val now = Instant.now()
        val header = base64UrlEncode("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val payload = base64UrlEncode(
            """{"iat":${now.epochSecond - 60},"exp":${now.epochSecond + 600},"iss":${properties.requireAppId()}}"""
                .toByteArray(),
        )

        val signatureInput = "$header.$payload"
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(signatureInput.toByteArray())
        val signed = base64UrlEncode(sig.sign())

        return "$header.$payload.$signed"
    }

    companion object {
        private fun loadPrivateKey(path: Path): RSAPrivateKey {
            val pem = Files.readString(path)
            val keyContent = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")

            val keyBytes = Base64.getDecoder().decode(keyContent)
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            return KeyFactory.getInstance("RSA").generatePrivate(keySpec) as RSAPrivateKey
        }

        private fun base64UrlEncode(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    private data class CachedToken(
        val token: String,
        val expiresAt: Instant,
    ) {
        fun isExpiringSoon(): Boolean = Instant.now().isAfter(expiresAt.minusSeconds(600))
    }

    private data class InstallationTokenResponse(
        val token: String,
        val expiresAt: String,
    )
}

class GitHubAuthException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
