package app.epistola.suite.metadata

import app.epistola.suite.config.bindJsonb
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Key/value store for application-level settings and internal state. Values are
 * stored as JSONB so callers can keep structured payloads under a single key
 * (the installation row under key `installation` is the canonical example).
 */
@Service
class AppMetadataService(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
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
