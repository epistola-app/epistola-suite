package app.epistola.suite.tenantbackup

import app.epistola.suite.crypto.CredentialCipher
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Encrypts/decrypts the whole backup archive at rest, reusing the audited AES-256-GCM
 * [CredentialCipher] (`epistola.encryption.*` keyset, `enc:v1:` envelope, key rotation). The binary
 * ZIP is base64-wrapped so it fits the cipher's String API. This is a second, outer layer: the
 * credential columns *inside* the dump are already `enc:v1:` ciphertext.
 *
 * When encryption is disabled the cipher is pass-through, so [wrap] stores the base64 text and
 * [unwrap] decodes it — still a faithful round-trip, just not encrypted.
 *
 * Restore requires the same keyset to be present — the same constraint that already governs the
 * live database's encrypted credential columns, so no new operational risk.
 */
@Component
class TenantBackupCrypto(
    private val cipher: CredentialCipher,
) {
    fun wrap(archiveBytes: ByteArray): ByteArray {
        val base64 = Base64.getEncoder().encodeToString(archiveBytes)
        return cipher.encrypt(base64).toByteArray(StandardCharsets.UTF_8)
    }

    fun unwrap(artifactBytes: ByteArray): ByteArray {
        val envelope = String(artifactBytes, StandardCharsets.UTF_8)
        val base64 = cipher.decrypt(envelope)
        return Base64.getDecoder().decode(base64)
    }
}
