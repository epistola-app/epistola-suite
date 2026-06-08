package app.epistola.suite.support

import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.testing.IntegrationTestBase
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EntitlementStoreIT : IntegrationTestBase() {
    @Autowired
    private lateinit var metadata: AppMetadataService

    @Autowired
    private lateinit var jdbi: Jdbi

    private val store: EntitlementStore by lazy { EntitlementStore(metadata) }

    @BeforeEach
    fun clear() {
        jdbi.useHandle<Exception> { handle ->
            handle
                .createUpdate("DELETE FROM app_metadata WHERE key = :key")
                .bind("key", EntitlementStore.METADATA_KEY)
                .execute()
        }
    }

    @Test
    fun `load returns null when nothing is stored`() {
        assertNull(store.load())
    }

    @Test
    fun `save then load round-trips entries including effect, scope and expiry`() {
        val fetchedAt = Instant.parse("2026-06-08T02:00:00Z")
        val expiresAt = Instant.parse("2027-01-01T00:00:00Z")
        val stored =
            StoredEntitlements(
                entries =
                listOf(
                    StoredEntitlement("support-backups", tenant = null, effect = EntitlementEffect.ALLOW, expiresAt = null),
                    StoredEntitlement("support-feedback", tenant = "acme", effect = EntitlementEffect.DENY, expiresAt = expiresAt),
                ),
                fetchedAt = fetchedAt,
            )

        store.save(stored)
        val loaded = store.load()

        assertEquals(stored, loaded, "Stored entitlements must round-trip through app_metadata JSONB")
    }

    @Test
    fun `save twice overwrites the previous set (last-known-good replacement)`() {
        val first = StoredEntitlements(listOf(StoredEntitlement("support-backups", null, EntitlementEffect.ALLOW, null)), Instant.parse("2026-06-08T01:00:00Z"))
        val second = StoredEntitlements(emptyList(), Instant.parse("2026-06-08T02:00:00Z"))

        store.save(first)
        store.save(second)

        assertEquals(second, store.load())
    }
}
