// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** A received catalog can be released here too (ADR 0032): its highest release is either pointer's. */
class CatalogHighestReleaseTest {

    private val at = OffsetDateTime.parse("2026-10-21T00:00:00Z")

    private fun catalog(released: String?, received: String?) = Catalog(
        id = CatalogKey.of("letters"),
        tenantKey = TenantKey.of("acme"),
        registry = "exchange.epistola.app",
        namespace = "acme",
        name = "Letters",
        type = CatalogType.SUBSCRIBED,
        releasedVersion = released,
        releasedFingerprint = released?.let { "released-$it" },
        installedReleaseVersion = received,
        installedFingerprint = received?.let { "received-$it" },
        contentUpdatedAt = at,
        createdAt = at,
        updatedAt = at,
    )

    @Test
    fun `the higher of what was released here and what was received, by version number`() {
        assertThat(catalog(released = "1.10.0", received = "1.9.0").highestReleaseVersion).isEqualTo("1.10.0")
        assertThat(catalog(released = "1.0.1", received = "2.0.0").let { it.highestReleaseVersion to it.highestReleaseFingerprint })
            .isEqualTo("2.0.0" to "received-2.0.0")
        assertThat(catalog(released = "1.0.1", received = "1.0.0").highestReleaseFingerprint).isEqualTo("released-1.0.1")
        assertThat(catalog(released = null, received = "1.0.0").highestReleaseVersion).isEqualTo("1.0.0")
        assertThat(catalog(released = null, received = null).highestReleaseVersion).isNull()
    }
}
