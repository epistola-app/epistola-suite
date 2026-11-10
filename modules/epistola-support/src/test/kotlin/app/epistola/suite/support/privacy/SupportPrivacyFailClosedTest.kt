// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.privacy

import app.epistola.suite.crypto.CredentialCipher
import app.epistola.suite.mediator.execute
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.support.SupportProperties
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The privacy settings never loosen because the database could not be read: a failed read keeps the
 * settings last read, and before any was read the strictest apply, never the configuration's.
 */
@Isolated("The privacy settings apply to the whole installation")
class SupportPrivacyFailClosedTest : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var cipher: CredentialCipher

    /** A configuration looser than the strictest. */
    private val loose = SupportProperties(privacy = SupportProperties.Privacy(tenantIdentity = "named", telemetry = "full"))

    private inner class Flaky : AppMetadataService(jdbi, objectMapper, cipher) {
        @Volatile var down = false

        override fun get(key: String): JsonNode? = if (down) error("the database is unreachable") else super.get(key)
    }

    @Test
    fun `before any read the strictest settings apply, without reading the database`() {
        val privacy = SupportPrivacy(loose, jdbi, objectMapper)

        val first = privacy.current()

        assertThat(first.tenantIdentity).isEqualTo(TenantIdentity.CONFIDENTIAL)
        assertThat(first.telemetry).isEqualTo(TelemetryDetail.PSEUDONYMISED)
        assertThat(privacy.forConnection(java.util.UUID.randomUUID()).confidential).`as`("a connection follows the strictest before any read").isTrue()
    }

    @Test
    fun `a tenant reference for the telemetry legs never reads the database, and is there once the secret is loaded`() {
        val metadata = Flaky().apply { down = true }
        val references = TenantReferences(metadata, SupportPrivacy(loose, jdbi, objectMapper))

        assertThat(references.referenceIfReady("acme")).`as`("not loaded, and not loaded on this thread").isNull()
        references.loadSecret()
        assertThat(references.referenceIfReady("acme")).`as`("the database is still down").isNull()

        metadata.down = false
        references.loadSecret()
        assertThat(references.referenceIfReady("acme")).isEqualTo(references.reference("acme")).startsWith(TenantReferences.PREFIX)
    }
}
