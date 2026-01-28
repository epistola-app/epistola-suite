package app.epistola.suite.metadata

import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Service

@Service
class AppMetadataService(
    private val jdbi: Jdbi,
) {
    fun get(key: String): String? = jdbi.withHandle<String?, Exception> { handle ->
        handle
            .createQuery(
                """
                    SELECT value FROM app_metadata
                    WHERE key = :key
                    """,
            ).bind("key", key)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)
    }

    fun set(
        key: String,
        value: String,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO app_metadata (key, value, updated_at)
                    VALUES (:key, :value, NOW())
                    ON CONFLICT (key) DO UPDATE
                    SET value = EXCLUDED.value, updated_at = NOW()
                    """,
                ).bind("key", key)
                .bind("value", value)
                .execute()
        }
    }
}
