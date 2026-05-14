package app.epistola.suite.support

import app.epistola.hub.client.port.InstallationCredentials
import app.epistola.suite.testing.IntegrationTestBase
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JdbcInstallationStoreIT : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    private val store: JdbcInstallationStore by lazy { JdbcInstallationStore(jdbi) }

    @Test
    fun `load returns null when no credentials are persisted`() {
        clearTable()
        assertNull(store.load())
    }

    @Test
    fun `save then load round-trips credentials`() {
        clearTable()
        val credentials = InstallationCredentials(
            installationId = UUID.randomUUID(),
            apiKey = "ek_test_abc123",
        )

        store.save(credentials)
        val loaded = store.load()

        assertNotNull(loaded)
        assertEquals(credentials.installationId, loaded.installationId)
        assertEquals(credentials.apiKey, loaded.apiKey)
    }

    @Test
    fun `save twice updates the singleton row`() {
        clearTable()
        val first = InstallationCredentials(UUID.randomUUID(), "ek_first")
        val second = InstallationCredentials(UUID.randomUUID(), "ek_second")

        store.save(first)
        store.save(second)

        val loaded = store.load()
        assertEquals(second, loaded, "Second save must overwrite the singleton row")
    }

    @Test
    fun `singleton CHECK rejects a second row`() {
        clearTable()
        // Bypass the store and try to insert a second row directly — the CHECK on id
        // should reject it.
        assertFailsWith<Exception> {
            jdbi.useHandle<Exception> { handle ->
                handle.execute(
                    """
                    INSERT INTO epistola_support_hub_credentials (id, installation_id, api_key)
                    VALUES (2, ?, ?)
                    """,
                    UUID.randomUUID(),
                    "ek_should_fail",
                )
            }
        }
    }

    private fun clearTable() {
        jdbi.useHandle<Exception> { handle ->
            handle.execute("DELETE FROM epistola_support_hub_credentials")
        }
    }
}
