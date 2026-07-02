package app.epistola.suite.tenantbackup

import app.epistola.suite.crypto.CredentialCipher
import app.epistola.suite.crypto.CredentialCipherFactory
import app.epistola.suite.crypto.EncryptionException
import app.epistola.suite.crypto.EncryptionProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * [TenantBackupCrypto] is the at-rest layer for backup artifacts. With encryption enabled it must
 * round-trip, reject a **non-envelope** artifact (a plain blob would bypass AEAD entirely), and
 * reject a **tampered** envelope (GCM authentication). With encryption disabled it is a faithful
 * pass-through.
 */
class TenantBackupCryptoTest {
    private fun enabledCipher(): CredentialCipher = CredentialCipherFactory.create(
        EncryptionProperties(
            enabled = true,
            primaryKeyId = "k1",
            keys = listOf(EncryptionProperties.KeyMaterial("k1", key())),
        ),
        isProdProfile = false,
    )

    private fun disabledCipher(): CredentialCipher = CredentialCipherFactory.create(EncryptionProperties(enabled = false), isProdProfile = false)

    private fun key(): String = Base64.getEncoder().encodeToString(ByteArray(CredentialCipher.KEY_BYTES) { it.toByte() })

    @Test
    fun `wrap then unwrap round-trips the archive bytes (enabled)`() {
        val crypto = TenantBackupCrypto(enabledCipher())
        val archive = ByteArray(2048) { (it * 7).toByte() }

        assertThat(crypto.unwrap(crypto.wrap(archive))).isEqualTo(archive)
    }

    @Test
    fun `unwrap refuses a non-envelope artifact when encryption is enabled`() {
        val crypto = TenantBackupCrypto(enabledCipher())
        // A plain (non-`enc:`) base64 blob would otherwise pass through the cipher unauthenticated.
        val plain = Base64.getEncoder().encodeToString("not encrypted".toByteArray()).toByteArray(StandardCharsets.UTF_8)

        assertThatThrownBy { crypto.unwrap(plain) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("authenticated ciphertext")
    }

    @Test
    fun `unwrap rejects a tampered envelope (GCM authentication)`() {
        val crypto = TenantBackupCrypto(enabledCipher())
        val envelope = String(crypto.wrap(ByteArray(512) { it.toByte() }), StandardCharsets.UTF_8)

        // Corrupt the ciphertext by swapping one character of the base64 body (past the
        // `enc:v1:k1:` header) for a *different but still valid* base64 char. This deterministically
        // alters the decoded ciphertext while keeping the envelope decodable, so the failure is the
        // GCM authentication tag — not a base64 decode error. (Flipping a raw byte of the base64
        // text would intermittently produce an invalid char, since the nonce — and thus the body —
        // is random per wrap.)
        val payloadStart = envelope.lastIndexOf(':') + 1
        val pos = payloadStart + 8 // safely inside the body, never the trailing `=` padding
        val replacement = if (envelope[pos] == 'A') 'B' else 'A'
        val tampered = StringBuilder(envelope).also { it.setCharAt(pos, replacement) }
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        assertThatThrownBy { crypto.unwrap(tampered) }.isInstanceOf(EncryptionException::class.java)
    }

    @Test
    fun `pass-through round-trips when encryption is disabled`() {
        val crypto = TenantBackupCrypto(disabledCipher())
        val archive = ByteArray(1024) { (it + 3).toByte() }

        // Round-trips, and the disabled cipher does not enforce the envelope (OSS deployments).
        assertThat(crypto.unwrap(crypto.wrap(archive))).isEqualTo(archive)
    }
}
