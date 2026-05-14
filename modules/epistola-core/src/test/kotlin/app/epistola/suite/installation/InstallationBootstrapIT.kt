package app.epistola.suite.installation

import app.epistola.suite.testing.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class InstallationBootstrapIT : IntegrationTestBase() {
    @Autowired
    private lateinit var installations: InstallationService

    @Test
    fun `installation row exists after context startup`() {
        // InstallationBootstrap (ApplicationRunner @Order(0)) ran during context startup,
        // so the installation row is already persisted.
        val installation = installations.getOrCreate()
        assertNotNull(installation)
        // UUIDv7 sets the version nibble to 7.
        assertEquals(7, installation.id.version(), "Installation id must be UUIDv7")
    }

    @Test
    fun `getOrCreate is idempotent`() {
        val first = installations.getOrCreate()
        val second = installations.getOrCreate()
        assertEquals(first, second, "Repeated getOrCreate must return the same installation")
    }
}
