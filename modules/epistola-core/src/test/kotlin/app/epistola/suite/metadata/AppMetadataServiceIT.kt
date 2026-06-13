package app.epistola.suite.metadata

import app.epistola.suite.testing.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.IntNode
import tools.jackson.databind.node.StringNode
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppMetadataServiceIT : IntegrationTestBase() {
    @Autowired
    private lateinit var metadata: AppMetadataService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `set then get round-trips a JsonNode`() {
        val key = uniqueKey("simple")
        metadata.set(key, StringNode.valueOf("hello"))
        val node = metadata.get(key)
        assertNotNull(node)
        assertEquals("hello", node.asString())
    }

    @Test
    fun `setAs then getAs round-trips a typed value`() {
        val key = uniqueKey("typed")
        val payload = Sample(name = "epistola", count = 42)
        metadata.setAs(key, payload)
        val loaded = metadata.getAs<Sample>(key)
        assertEquals(payload, loaded)
    }

    @Test
    fun `set overwrites existing value`() {
        val key = uniqueKey("overwrite")
        metadata.set(key, IntNode.valueOf(1))
        metadata.set(key, IntNode.valueOf(2))
        assertEquals(2, metadata.get(key)?.asInt())
    }

    @Test
    fun `setIfAbsent returns true on insert and false on conflict`() {
        val key = uniqueKey("absent")
        assertTrue(metadata.setIfAbsent(key, Sample("first", 1)))
        // Second call must NOT overwrite — proves the ON CONFLICT DO NOTHING contract that
        // InstallationService relies on for safe multi-pod first-boot.
        assertFalse(metadata.setIfAbsent(key, Sample("second", 2)))
        assertEquals(Sample("first", 1), metadata.getAs<Sample>(key))
    }

    @Test
    fun `get returns null when key is absent`() {
        assertNull(metadata.get(uniqueKey("missing")))
        assertNull(metadata.getAs<Sample>(uniqueKey("missing")))
    }

    @Test
    fun `setEncrypted stores ciphertext and getEncryptedAs round-trips`() {
        val key = uniqueKey("encrypted")
        val payload = Sample(name = "hub-token-xyz", count = 7)
        metadata.setEncrypted(key, payload)

        // Round-trips through decryption.
        assertEquals(payload, metadata.getEncryptedAs<Sample>(key))

        // The stored JSONB cell is an enc: envelope string, not the plaintext.
        val storedAsString = metadata.get(key)?.asString()
        assertNotNull(storedAsString)
        assertTrue(storedAsString.startsWith("enc:v1:"), "expected ciphertext envelope, got: $storedAsString")
        assertFalse(storedAsString.contains("hub-token-xyz"), "plaintext leaked into storage")
    }

    @Test
    fun `getEncryptedAs returns null when key is absent`() {
        assertNull(metadata.getEncryptedAs<Sample>(uniqueKey("missing-enc")))
    }

    private fun uniqueKey(prefix: String): String = "test_${prefix}_${UUID.randomUUID().toString().substring(0, 8)}"

    data class Sample(val name: String, val count: Int)
}
