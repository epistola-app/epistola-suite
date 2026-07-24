// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.metadata

import app.epistola.suite.config.bindJsonb
import app.epistola.suite.crypto.CredentialCipher
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Key/value store for application-level settings and internal state. Values are
 * stored as JSONB so callers can keep structured payloads under a single key
 * (the installation row under key `installation` is the canonical example).
 *
 * For sensitive entries (e.g. hub credentials) use [setEncrypted] /
 * [getEncryptedAs]: the value is serialized, encrypted via [CredentialCipher],
 * and stored as an `enc:` JSON string inside the JSONB cell — keeping the plain
 * [get]/[set] path crypto-agnostic.
 */
@Service
class AppMetadataService(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val credentialCipher: CredentialCipher,
) {
    fun get(key: String): JsonNode? = jdbi.withHandle<JsonNode?, Exception> { handle ->
        handle
            .createQuery("SELECT value::text FROM app_metadata WHERE key = :key")
            .bind("key", key)
            .mapTo(String::class.java)
            .findOne()
            .map { objectMapper.readTree(it) }
            .orElse(null)
    }

    fun <T : Any> getAs(key: String, type: Class<T>): T? = get(key)?.let { node ->
        objectMapper.treeToValue(node, type)
    }

    fun set(key: String, value: JsonNode) {
        jdbi.useHandle<Exception> { handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO app_metadata (key, value, updated_at)
                    VALUES (:key, :value::jsonb, NOW())
                    ON CONFLICT (key) DO UPDATE
                    SET value = EXCLUDED.value, updated_at = NOW()
                    """,
                ).bind("key", key)
                .bindJsonb("value", value, objectMapper)
                .execute()
        }
    }

    fun setAs(key: String, value: Any) {
        set(key, objectMapper.valueToTree(value))
    }

    /**
     * Like [setAs] but encrypts the serialized value at rest. The ciphertext
     * envelope is stored as a JSON string inside the JSONB cell. Read back with
     * [getEncryptedAs].
     */
    fun setEncrypted(key: String, value: Any) {
        val json = objectMapper.writeValueAsString(value)
        val envelope = credentialCipher.encrypt(json)
        set(key, objectMapper.valueToTree(envelope))
    }

    /** Reads and decrypts a value written by [setEncrypted]. Null if absent. */
    fun <T : Any> getEncryptedAs(key: String, type: Class<T>): T? {
        val node = get(key) ?: return null
        val envelope = objectMapper.treeToValue(node, String::class.java)
        val json = credentialCipher.decrypt(envelope)
        return objectMapper.readValue(json, type)
    }

    /**
     * Inserts only when the key is absent. Returns true if the row was inserted,
     * false if a row already existed. Used by single-write-wins bootstraps
     * (e.g. the installation identity) so concurrent pods don't overwrite
     * each other's first-boot value.
     */
    fun setIfAbsent(key: String, value: Any): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle
            .createUpdate(
                """
                INSERT INTO app_metadata (key, value, updated_at)
                VALUES (:key, :value::jsonb, NOW())
                ON CONFLICT (key) DO NOTHING
                """,
            ).bind("key", key)
            .bindJsonb("value", value, objectMapper)
            .execute() == 1
    }
}

/** Reified convenience for [AppMetadataService.getAs]. */
inline fun <reified T : Any> AppMetadataService.getAs(key: String): T? = getAs(key, T::class.java)

/** Reified convenience for [AppMetadataService.getEncryptedAs]. */
inline fun <reified T : Any> AppMetadataService.getEncryptedAs(key: String): T? = getEncryptedAs(key, T::class.java)
