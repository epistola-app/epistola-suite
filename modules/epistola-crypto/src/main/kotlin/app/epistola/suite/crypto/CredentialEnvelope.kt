package app.epistola.suite.crypto

import java.util.Base64

/**
 * The self-describing at-rest format for an encrypted credential:
 *
 * ```
 * enc:v1:<keyId>:<base64url(nonce ‖ ciphertext ‖ gcmTag)>
 * ```
 *
 * - `enc:` is the sentinel that distinguishes ciphertext from a legacy plaintext
 *   value. Anything not starting with `enc:` is treated as plaintext on read.
 * - `v1` is the scheme version, letting the algorithm or layout change later.
 * - `<keyId>` selects the decryption key and is also fed to AES-GCM as AAD, so it
 *   cannot be swapped without failing the authentication tag. It must not contain
 *   `:`.
 *
 * The whole string fits in the existing `TEXT` credential columns.
 */
object CredentialEnvelope {
    const val PREFIX = "enc:"
    const val VERSION = "v1"
    private const val SCHEME_PREFIX = "$PREFIX$VERSION:" // "enc:v1:"

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** True if [raw] is an encrypted envelope (vs. a legacy plaintext value). */
    fun isEnvelope(raw: String): Boolean = raw.startsWith(PREFIX)

    fun format(keyId: String, payload: ByteArray): String = SCHEME_PREFIX + keyId + ":" + encoder.encodeToString(payload)

    /** Parsed envelope: the embedded [keyId] and the raw [payload] (nonce ‖ ct ‖ tag). */
    data class Parsed(val keyId: String, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Parsed && keyId == other.keyId && payload.contentEquals(other.payload))

        override fun hashCode(): Int = 31 * keyId.hashCode() + payload.contentHashCode()
    }

    fun parse(raw: String): Parsed {
        require(raw.startsWith(SCHEME_PREFIX)) {
            "Unsupported credential envelope (expected '$SCHEME_PREFIX' prefix)"
        }
        val rest = raw.substring(SCHEME_PREFIX.length)
        val sep = rest.indexOf(':')
        require(sep > 0) { "Malformed credential envelope: missing keyId separator" }
        val keyId = rest.substring(0, sep)
        val payload = decoder.decode(rest.substring(sep + 1))
        return Parsed(keyId, payload)
    }
}
