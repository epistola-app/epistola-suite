package app.epistola.suite.feedback.sync.github

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies GitHub webhook HMAC-SHA256 signatures.
 */
object GitHubWebhookVerifier {

    fun verify(payload: ByteArray, signature: String, secret: String): Boolean {
        if (!signature.startsWith("sha256=")) return false

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val computed = mac.doFinal(payload)
        val expected = signature.removePrefix("sha256=").hexToByteArray()

        return computed.contentEquals(expected)
    }

    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
