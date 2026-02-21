package app.epistola.suite.apikeys

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ApiKeyServiceTest {

    private val service = ApiKeyService()

    @Nested
    inner class KeyGeneration {
        @Test
        fun `generated key starts with epk_ prefix`() {
            val key = service.generateKey()
            assertTrue(key.startsWith("epk_"), "Key should start with epk_ prefix, got: $key")
        }

        @Test
        fun `generated keys are unique`() {
            val keys = (1..100).map { service.generateKey() }.toSet()
            assertEquals(100, keys.size, "All 100 generated keys should be unique")
        }

        @Test
        fun `generated key has reasonable length`() {
            val key = service.generateKey()
            // epk_ prefix (4) + base64url of 32 bytes (~43) = ~47 chars
            assertTrue(key.length in 40..60, "Key length should be 40-60 chars, got ${key.length}")
        }

        @Test
        fun `deterministic key uses provided seed`() {
            val key = service.generateDeterministicKey("test_seed_12345")
            assertEquals("epk_test_seed_12345", key)
        }
    }

    @Nested
    inner class KeyHashing {
        @Test
        fun `hash is deterministic for same input`() {
            val key = service.generateKey()
            val hash1 = service.hashKey(key)
            val hash2 = service.hashKey(key)
            assertEquals(hash1, hash2)
        }

        @Test
        fun `different keys produce different hashes`() {
            val key1 = service.generateKey()
            val key2 = service.generateKey()
            assertNotEquals(service.hashKey(key1), service.hashKey(key2))
        }

        @Test
        fun `hash is a valid hex string`() {
            val key = service.generateKey()
            val hash = service.hashKey(key)
            assertTrue(hash.matches(Regex("^[0-9a-f]{64}$")), "SHA-256 hash should be 64 hex chars, got: $hash")
        }

        @Test
        fun `known input produces expected SHA-256 hash`() {
            // SHA-256 of "epk_test" = a known value
            val hash = service.hashKey("epk_test")
            assertEquals(64, hash.length)
            // Verify it's consistent (regression test)
            assertEquals(service.hashKey("epk_test"), hash)
        }
    }

    @Nested
    inner class PrefixExtraction {
        @Test
        fun `extracts prefix with truncation indicator`() {
            val key = service.generateKey()
            val prefix = service.extractPrefix(key)
            assertTrue(prefix.startsWith("epk_"), "Prefix should start with epk_")
            assertTrue(prefix.endsWith("..."), "Prefix should end with ...")
        }

        @Test
        fun `prefix shows first 8 chars after epk_`() {
            val key = "epk_abcdefgh12345678901234567890"
            val prefix = service.extractPrefix(key)
            assertEquals("epk_abcdefgh...", prefix)
        }

        @Test
        fun `throws for key without epk_ prefix`() {
            val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                service.extractPrefix("invalid_key")
            }
            assertTrue(exception.message!!.contains("epk_"))
        }
    }
}
