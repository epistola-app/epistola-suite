// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.support.connection.SupportConnection
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.SupportConnectionStatus
import app.epistola.suite.support.connection.SupportScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/** The catalog mirror's pure rules: who shares what, the back-off, the wire set and what the page offers. */
class CatalogMirrorRulesTest {

    @ParameterizedTest(name = "shareByDefault={0}, policy={1} -> {2}")
    @CsvSource(
        "true, INHERIT, SHARED",
        "true, SHARE, SHARED",
        "true, NEVER, WITHHELD",
        "false, INHERIT, NOT_MARKED",
        "false, SHARE, SHARED",
        "false, NEVER, WITHHELD",
        ", SHARE, TENANT_NOT_SHARING",
        ", INHERIT, TENANT_NOT_SHARING",
    )
    fun `the sharing rule`(shareByDefault: Boolean?, policy: CatalogMirrorPolicy, expected: CatalogMirrorSharing) {
        assertThat(CatalogMirrorSharing.resolve(shareByDefault, policy)).isEqualTo(expected)
    }

    @Test
    fun `back-off doubles from the interval per failure and is capped`() {
        val interval = Duration.ofMinutes(15)
        assertThat(CatalogMirrorReconciler.backoff(interval, 1)).isEqualTo(Duration.ofMinutes(15))
        assertThat(CatalogMirrorReconciler.backoff(interval, 2)).isEqualTo(Duration.ofMinutes(30))
        assertThat(CatalogMirrorReconciler.backoff(interval, 3)).isEqualTo(Duration.ofHours(1))
        assertThat(CatalogMirrorReconciler.backoff(interval, 10)).isEqualTo(CatalogMirror.MAX_BACKOFF)
        assertThat(CatalogMirrorReconciler.backoff(interval, 10_000)).isEqualTo(CatalogMirror.MAX_BACKOFF)
    }

    @Test
    fun `the wire set carries the protocol's fields and the same set has the same digest`() {
        val client = CatalogMirrorClient(JsonMapper.builder().build())
        val binding = MirrorBinding(
            catalogRowId = UUID.randomUUID(),
            versionRowId = UUID.randomUUID(),
            catalog = CatalogId("default", CatalogKey.of("letters"), TenantId(TenantKey.of("acme"))),
            registry = "local",
            version = "1.3.0",
            contentHash = "v6:${"9".repeat(64)}",
            releasedAt = OffsetDateTime.parse("2026-11-02T11:15:00+01:00"),
            deployments = listOf(MirrorDeployment("prod", true)),
            dependencies = listOf(MirrorDependency("exchange.epistola.app", "acme", "base", "2.0.0", "v6:${"1".repeat(64)}")),
        )

        val set = client.tenantSet(listOf(binding))
        val json = JsonMapper.builder().build().readTree(set.body)

        val sent = json.path("bindings")[0]
        assertThat(sent.path("registry").asString()).isEqualTo("local")
        assertThat(sent.path("namespace").asString()).isEqualTo("default")
        assertThat(sent.path("key").asString()).isEqualTo("letters")
        assertThat(sent.path("version").asString()).isEqualTo("1.3.0")
        assertThat(sent.path("contentHash").asString()).isEqualTo(binding.contentHash)
        assertThat(sent.path("releasedAt").asString()).isEqualTo("2026-11-02T10:15:00Z")
        assertThat(sent.path("deployments")[0].path("environment").asString()).isEqualTo("prod")
        assertThat(sent.path("deployments")[0].path("production").asBoolean()).isTrue()
        assertThat(sent.path("dependencies")[0].path("registry").asString()).isEqualTo("exchange.epistola.app")
        assertThat(sent.path("dependencies")[0].path("contentHash").asString()).startsWith("v6:1")
        assertThat(sent.has("versionId")).`as`("only the protocol's fields").isFalse()

        assertThat(client.tenantSet(listOf(binding.copy(catalogRowId = UUID.randomUUID()))).digest).isEqualTo(set.digest)
        assertThat(client.tenantSet(listOf(binding.copy(version = "1.3.1"))).digest).isNotEqualTo(set.digest)
        assertThat(client.tenantSet(emptyList()).body.toString(Charsets.UTF_8)).isEqualTo("""{"bindings":[]}""")
    }

    @Test
    fun `the page offers opting in only with the support connection on, and says why not otherwise`() {
        fun settings(availability: CatalogMirrorAvailability, consent: CatalogMirrorConsent? = null, canChange: Boolean = true) = CatalogMirrorSettings(
            tenantKey = TenantKey.of("acme"),
            availability = availability,
            consent = consent,
            round = null,
            catalogs = emptyList(),
            totalCatalogs = 0,
            offset = 0,
            limit = 50,
            canChangeSharing = canChange,
            canWithhold = true,
        )
        val sharing = CatalogMirrorConsent(true, CatalogMirror.CONSENT_VERSION, OffsetDateTime.parse("2026-11-06T10:00:00Z"), "Jane", null, null)

        assertThat(settings(CatalogMirrorAvailability.SUPPORT_OFF).canOptIn).isFalse()
        assertThat(settings(CatalogMirrorAvailability.SUPPORT_OFF).optInWithheldReason).contains("support connection on")
        assertThat(settings(CatalogMirrorAvailability.MIRROR_OFF).optInWithheldReason).contains("catalog mirror on")
        assertThat(settings(CatalogMirrorAvailability.PENDING).canOptIn).`as`("takes effect once granted").isTrue()
        assertThat(settings(CatalogMirrorAvailability.AVAILABLE, canChange = false).optInWithheldReason).contains("Only a tenant administrator")

        assertThat(settings(CatalogMirrorAvailability.PENDING, sharing).canSyncNow).isFalse()
        assertThat(settings(CatalogMirrorAvailability.PENDING, sharing).syncWithheldReason).contains("activated")
        assertThat(settings(CatalogMirrorAvailability.NOT_GRANTED, sharing).syncWithheldReason).contains("granted")
        assertThat(settings(CatalogMirrorAvailability.AVAILABLE, sharing).canSyncNow).isTrue()
        assertThat(settings(CatalogMirrorAvailability.AVAILABLE).canSyncNow).`as`("nothing to sync").isFalse()
        assertThat(settings(CatalogMirrorAvailability.AVAILABLE, sharing).canOptOut).isTrue()
        assertThat(settings(CatalogMirrorAvailability.SUPPORT_OFF, sharing).canOptOut).`as`("stopping is always possible").isTrue()
    }

    @Test
    fun `policy options keep the current one and add only what the viewer may set`() {
        val entry = CatalogMirrorEntry(
            catalog = CatalogId("default", CatalogKey.of("letters"), TenantId(TenantKey.of("acme"))),
            registry = "local",
            name = "Letters",
            policy = CatalogMirrorPolicy.NEVER,
            sharing = CatalogMirrorSharing.WITHHELD,
            releases = 1,
            lastSyncedAt = null,
        )
        val base = CatalogMirrorSettings(TenantKey.of("acme"), CatalogMirrorAvailability.AVAILABLE, null, null, listOf(entry), 1, 0, 50, canChangeSharing = false, canWithhold = true)

        assertThat(base.policyOptions(entry)).containsExactly(CatalogMirrorPolicy.NEVER)
        assertThat(base.canSetPolicy(entry)).isFalse()
        assertThat(base.copy(canChangeSharing = true).policyOptions(entry)).containsExactly(CatalogMirrorPolicy.INHERIT, CatalogMirrorPolicy.SHARE, CatalogMirrorPolicy.NEVER)
    }
}
