package app.epistola.suite.support

import app.epistola.hub.client.port.InstallationCredentials
import app.epistola.hub.client.port.InstallationStore
import org.jdbi.v3.core.Jdbi
import java.util.UUID

/**
 * Persists the singleton hub-issued credentials row in the
 * `epistola_support_hub_credentials` table. The CHECK constraint
 * `id = 1` enforces the singleton invariant at the database layer.
 */
class JdbcInstallationStore(
    private val jdbi: Jdbi,
) : InstallationStore {
    override fun load(): InstallationCredentials? = jdbi.withHandle<InstallationCredentials?, Exception> { handle ->
        handle
            .createQuery(
                """
                SELECT installation_id, api_key
                FROM epistola_support_hub_credentials
                WHERE id = 1
                """,
            ).map { rs, _ ->
                InstallationCredentials(
                    installationId = rs.getObject("installation_id", UUID::class.java),
                    apiKey = rs.getString("api_key"),
                )
            }.findOne()
            .orElse(null)
    }

    override fun save(credentials: InstallationCredentials) {
        jdbi.useHandle<Exception> { handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO epistola_support_hub_credentials (id, installation_id, api_key, created_at)
                    VALUES (1, :installationId, :apiKey, NOW())
                    ON CONFLICT (id) DO UPDATE
                    SET installation_id = EXCLUDED.installation_id,
                        api_key         = EXCLUDED.api_key,
                        updated_at      = NOW()
                    """,
                ).bind("installationId", credentials.installationId)
                .bind("apiKey", credentials.apiKey)
                .execute()
        }
    }
}
