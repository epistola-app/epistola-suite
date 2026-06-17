package app.epistola.suite.crypto

import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

/**
 * Builds a validated [CredentialCipher] from [EncryptionProperties], applying the
 * profile-dependent fallback policy:
 *
 * - encryption disabled            → pass-through cipher.
 * - keys configured                → validate and build a keyset cipher.
 * - no keys, prod profile          → fail fast (throws).
 * - no keys, non-prod profile      → ephemeral in-memory dev key + loud warning.
 *
 * All key material is validated to be exactly 32 bytes; errors name the offending
 * key id but never log the material itself.
 */
object CredentialCipherFactory {
    const val DEV_KEY_ID = "dev-ephemeral"

    private val logger = LoggerFactory.getLogger(CredentialCipherFactory::class.java)
    private val secureRandom = SecureRandom()

    fun create(properties: EncryptionProperties, isProdProfile: Boolean): CredentialCipher {
        if (!properties.enabled) {
            logger.warn(
                "Credential encryption is DISABLED (epistola.encryption.enabled=false) — credentials are stored as plaintext.",
            )
            return CredentialCipher(enabled = false, keys = emptyMap(), primaryKeyId = "").also { it.selfTest() }
        }

        if (properties.keys.isEmpty()) {
            check(!isProdProfile) {
                "epistola.encryption.keys must be configured in the prod profile " +
                    "(generate one with `openssl rand -base64 32`)"
            }
            logger.warn(
                "No encryption keys configured — generating an EPHEMERAL in-memory dev key. " +
                    "Encrypted data will NOT be readable after a restart. Configure epistola.encryption.keys " +
                    "for any persistent environment.",
            )
            val material = ByteArray(CredentialCipher.KEY_BYTES).also(secureRandom::nextBytes)
            val keys = mapOf(DEV_KEY_ID to SecretKeySpec(material, "AES"))
            return CredentialCipher(enabled = true, keys = keys, primaryKeyId = DEV_KEY_ID).also { it.selfTest() }
        }

        val keys = LinkedHashMap<String, SecretKeySpec>()
        for (key in properties.keys) {
            require(key.id.isNotBlank()) { "An encryption key id must not be blank" }
            require(':' !in key.id) { "Encryption key id '${key.id}' must not contain ':'" }
            require(key.id !in keys) { "Duplicate encryption key id '${key.id}'" }
            val material = try {
                Base64.getDecoder().decode(key.material.trim())
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("Encryption key '${key.id}' material is not valid base64", e)
            }
            require(material.size == CredentialCipher.KEY_BYTES) {
                "Encryption key '${key.id}' must decode to ${CredentialCipher.KEY_BYTES} bytes (256-bit), got ${material.size}"
            }
            keys[key.id] = SecretKeySpec(material, "AES")
        }

        require(properties.primaryKeyId.isNotBlank()) { "epistola.encryption.primary-key-id must be set" }
        require(properties.primaryKeyId in keys) {
            "epistola.encryption.primary-key-id '${properties.primaryKeyId}' is not among the configured keys ${keys.keys}"
        }

        logger.info(
            "Credential encryption enabled with {} key(s); primary key id '{}'.",
            keys.size,
            properties.primaryKeyId,
        )
        return CredentialCipher(enabled = true, keys = keys, primaryKeyId = properties.primaryKeyId).also { it.selfTest() }
    }
}
