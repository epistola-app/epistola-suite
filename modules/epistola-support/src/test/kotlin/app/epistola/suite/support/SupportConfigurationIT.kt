// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.hub.client.EpistolaHubClient
import app.epistola.hub.client.InstallationMetadata
import app.epistola.hub.client.port.InstallationStore
import app.epistola.suite.testing.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Verifies the support tier wires correctly when enabled and the required
 * installation properties are present.
 *
 * A stub [EpistolaHubClient] replaces the real bean so `loop.start()` —
 * triggered on `ApplicationReadyEvent` during context startup — does not
 * attempt real gRPC against a hub that doesn't exist in CI.
 */
@TestPropertySource(
    properties = [
        "epistola.support.enabled=true",
        "epistola.installation.companyName=Acme",
        "epistola.installation.adminEmail=ops@acme.example",
        "epistola.installation.environment=test",
    ],
)
@Import(SupportConfigurationIT.StubHubClientConfiguration::class)
class SupportConfigurationIT : IntegrationTestBase() {
    @Autowired
    private lateinit var installationMetadata: InstallationMetadata

    @Autowired
    private lateinit var hubClient: EpistolaHubClient

    @Test
    fun `installation metadata resolves name from companyName and environment`() {
        assertEquals("Acme - test", installationMetadata.name)
    }

    @Test
    fun `installation metadata carries the installation id`() {
        assertNotNull(installationMetadata.installationId)
        // V30 builds a UUIDv7 in pure SQL — the hub server enforces v7 at
        // registration time.
        assertEquals(7, installationMetadata.installationId.version())
    }

    @Test
    fun `stub hub client is wired in place of the real one`() {
        // Sanity check that our TestConfiguration override is active.
        assertEquals(StubHubClient::class, hubClient::class)
    }

    @TestConfiguration
    class StubHubClientConfiguration {
        @Bean
        @Primary
        fun stubHubClient(store: InstallationStore): EpistolaHubClient = StubHubClient(store)
    }

    /**
     * Stub that overrides the protobuf `callRegister` so `ensureRegistered` —
     * triggered by `HubRegistrationRetryLoop.start()` on `ApplicationReadyEvent`
     * — completes without any network. `store.save()` runs against the real
     * JDBC store but only after Flyway has applied V31, so the table is present.
     */
    class StubHubClient(
        store: InstallationStore,
    ) : EpistolaHubClient(store = store, nodeId = "stub-node", discoveryUrl = "https://invalid.example/.well-known/epistola/hub.json") {
        override fun callRegister(request: app.epistola.hub.proto.v1.RegisterRequest): app.epistola.hub.proto.v1.RegisterResponse = app.epistola.hub.proto.v1.RegisterResponse
            .newBuilder()
            .setApiKey("ek_stub")
            .build()
    }
}
