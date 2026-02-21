package app.epistola.suite.apikeys

import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Service for API key generation and hashing.
 *
 * Keys have the format: `epk_<base62 characters>` (~47 characters total).
 * Only the SHA-256 hash is stored in the database.
 */
@Component
class ApiKeyService {

    private val secureRandom = SecureRandom()

    /**
     * Generates a new random API key with the `epk_` prefix.
     */
    fun generateKey(): String {
        val randomBytes = ByteArray(KEY_BYTES)
        secureRandom.nextBytes(randomBytes)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
        return "$KEY_PREFIX$encoded"
    }

    /**
     * Generates a deterministic key for use in demo/testing.
     */
    fun generateDeterministicKey(seed: String): String = "$KEY_PREFIX$seed"

    /**
     * Computes the SHA-256 hash of an API key for storage.
     */
    fun hashKey(plaintextKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(plaintextKey.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Extracts the prefix (first 8 chars after `epk_`) for display purposes.
     */
    fun extractPrefix(plaintextKey: String): String {
        require(plaintextKey.startsWith(KEY_PREFIX)) { "API key must start with $KEY_PREFIX" }
        val afterPrefix = plaintextKey.removePrefix(KEY_PREFIX)
        return "$KEY_PREFIX${afterPrefix.take(PREFIX_DISPLAY_CHARS)}..."
    }

    companion object {
        const val KEY_PREFIX = "epk_"
        private const val KEY_BYTES = 32
        private const val PREFIX_DISPLAY_CHARS = 8
    }
}
