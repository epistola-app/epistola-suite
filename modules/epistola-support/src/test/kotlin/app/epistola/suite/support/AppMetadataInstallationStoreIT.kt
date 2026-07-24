// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.hub.client.port.InstallationCredentials
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.testing.IntegrationTestBase
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppMetadataInstallationStoreIT : IntegrationTestBase() {
    @Autowired
    private lateinit var metadata: AppMetadataService

    @Autowired
    private lateinit var jdbi: Jdbi

    private val store: AppMetadataInstallationStore by lazy { AppMetadataInstallationStore(metadata) }

    @BeforeEach
    fun clearCredentials() {
        // Wipe the key so each test starts from a known absent state.
        jdbi.useHandle<Exception> { handle ->
            handle
                .createUpdate("DELETE FROM app_metadata WHERE key = :key")
                .bind("key", AppMetadataInstallationStore.METADATA_KEY)
                .execute()
        }
    }

    @Test
    fun `load returns null when no credentials are persisted`() {
        assertNull(store.load())
    }

    @Test
    fun `save then load round-trips credentials`() {
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
    fun `save twice overwrites the existing entry`() {
        val first = InstallationCredentials(UUID.randomUUID(), "ek_first")
        val second = InstallationCredentials(UUID.randomUUID(), "ek_second")

        store.save(first)
        store.save(second)

        val loaded = store.load()
        assertEquals(second, loaded, "Second save must overwrite the previous credentials")
    }
}
