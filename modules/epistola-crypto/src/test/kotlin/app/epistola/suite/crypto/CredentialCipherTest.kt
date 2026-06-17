package app.epistola.suite.crypto

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialCipherTest {

    private fun keyMaterial(seed: Byte): String = Base64.getEncoder().encodeToString(ByteArray(CredentialCipher.KEY_BYTES) { (it + seed).toByte() })

    private fun cipher(
        primary: String = "k1",
        keys: List<EncryptionProperties.KeyMaterial> = listOf(EncryptionProperties.KeyMaterial("k1", keyMaterial(1))),
    ): CredentialCipher = CredentialCipherFactory.create(
        EncryptionProperties(enabled = true, primaryKeyId = primary, keys = keys),
        isProdProfile = false,
    )

    @Test
    fun `round-trips plaintext`() {
        val c = cipher()
        for (value in listOf("", "s3cr3t", "üñîçødé 🔐", "x".repeat(5000))) {
            assertEquals(value, c.decrypt(c.encrypt(value)))
        }
    }

    @Test
    fun `produces a self-describing envelope under the primary key`() {
        val token = cipher().encrypt("hello")
        assertTrue(token.startsWith("enc:v1:k1:"), "unexpected envelope: $token")
        assertTrue(CredentialEnvelope.isEnvelope(token))
    }

    @Test
    fun `uses a fresh nonce per encryption`() {
        val c = cipher()
        val a = c.encrypt("same")
        val b = c.encrypt("same")
        assertTrue(a != b, "ciphertexts should differ due to random nonce")
        assertEquals("same", c.decrypt(a))
        assertEquals("same", c.decrypt(b))
    }

    @Test
    fun `old key still decrypts after primary rotates`() {
        val keys = listOf(
            EncryptionProperties.KeyMaterial("k1", keyMaterial(1)),
            EncryptionProperties.KeyMaterial("k2", keyMaterial(2)),
        )
        val underK1 = cipher(primary = "k1", keys = keys).encrypt("rotate-me")

        val rotated = cipher(primary = "k2", keys = keys)
        // Old ciphertext still readable...
        assertEquals("rotate-me", rotated.decrypt(underK1))
        // ...and new writes use the new primary.
        val underK2 = rotated.encrypt("fresh")
        assertTrue(underK2.startsWith("enc:v1:k2:"))
        assertEquals("k1", rotated.keyIdOf(underK1))
        assertEquals("k2", rotated.keyIdOf(underK2))
    }

    @Test
    fun `rejects tampered ciphertext`() {
        val c = cipher()
        val token = c.encrypt("authentic")
        // Flip a full byte in the GCM-tag region of the decoded payload, then
        // re-encode. (Flipping a trailing base64 *char* is unreliable: the final
        // group's unused padding bits are ignored by the decoder, so some flips
        // decode to identical bytes and the tag still verifies.)
        val parsed = CredentialEnvelope.parse(token)
        val mutated = parsed.payload.copyOf()
        mutated[mutated.size - 1] = (mutated[mutated.size - 1].toInt() xor 0xFF).toByte()
        val tampered = CredentialEnvelope.format(parsed.keyId, mutated)
        assertFailsWith<EncryptionException> { c.decrypt(tampered) }
    }

    @Test
    fun `rejects a swapped key-id label (AAD mismatch)`() {
        val keys = listOf(
            EncryptionProperties.KeyMaterial("k1", keyMaterial(1)),
            EncryptionProperties.KeyMaterial("k2", keyMaterial(2)),
        )
        val c = cipher(primary = "k1", keys = keys)
        val token = c.encrypt("bound-to-k1")
        val parsed = CredentialEnvelope.parse(token)
        // Re-label the envelope as k2 without re-encrypting; AAD no longer matches.
        val relabelled = CredentialEnvelope.format("k2", parsed.payload)
        assertFailsWith<EncryptionException> { c.decrypt(relabelled) }
    }

    @Test
    fun `passes legacy plaintext through on decrypt`() {
        assertEquals("not-encrypted", cipher().decrypt("not-encrypted"))
        assertNull(cipher().keyIdOf("not-encrypted"))
    }

    @Test
    fun `disabled cipher is a pass-through both ways`() {
        val c = CredentialCipherFactory.create(EncryptionProperties(enabled = false), isProdProfile = true)
        assertEquals("plain", c.encrypt("plain"))
        assertEquals("plain", c.decrypt("plain"))
    }

    @Test
    fun `dev fallback generates an ephemeral key when none configured`() {
        val c = CredentialCipherFactory.create(EncryptionProperties(), isProdProfile = false)
        assertEquals("works", c.decrypt(c.encrypt("works")))
    }

    @Test
    fun `prod profile fails fast with no keys`() {
        assertFailsWith<IllegalStateException> {
            CredentialCipherFactory.create(EncryptionProperties(), isProdProfile = true)
        }
    }

    @Test
    fun `rejects key material that is not 32 bytes`() {
        val short = Base64.getEncoder().encodeToString(ByteArray(16))
        assertFailsWith<IllegalArgumentException> {
            cipher(keys = listOf(EncryptionProperties.KeyMaterial("k1", short)))
        }
    }

    @Test
    fun `rejects a primary id absent from the keyset`() {
        assertFailsWith<IllegalArgumentException> {
            cipher(primary = "missing")
        }
    }

    @Test
    fun `rejects a key id containing a colon`() {
        assertFailsWith<IllegalArgumentException> {
            cipher(primary = "a:b", keys = listOf(EncryptionProperties.KeyMaterial("a:b", keyMaterial(1))))
        }
    }

    @Test
    fun `decrypt fails for an unknown key id in the envelope`() {
        val token = cipher(primary = "k1", keys = listOf(EncryptionProperties.KeyMaterial("k1", keyMaterial(1)))).encrypt("x")
        // A cipher that doesn't know k1.
        val other = cipher(primary = "k9", keys = listOf(EncryptionProperties.KeyMaterial("k9", keyMaterial(9))))
        assertFalse(token.contains("k9"))
        assertFailsWith<EncryptionException> { other.decrypt(token) }
    }
}
