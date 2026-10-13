// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import app.epistola.suite.exchange.ExchangeProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProductDiscoverySummaryTest {
    private val endpointed = "https://mirror.example/x/{id}"

    private fun inUse(
        versionCheck: VersionCheckProperties = VersionCheckProperties(),
        installationCheck: InstallationCheckProperties = InstallationCheckProperties(),
        sharing: SharingProperties = SharingProperties(),
        exchange: ExchangeProperties = ExchangeProperties(),
        fetchedBefore: Boolean = false,
    ) = ProductDiscoverySummary.inUse(versionCheck, installationCheck, sharing, exchange, fetchedBefore)

    @Test
    fun `the defaults use discovery`() {
        assertThat(inUse()).isTrue()
    }

    @Test
    fun `nothing that is off or has its own endpoint uses discovery`() {
        val none = inUse(
            versionCheck = VersionCheckProperties(enabled = false),
            sharing = SharingProperties(installationId = false, suiteVersion = false, usageStatistics = false),
        )
        val allConfigured = inUse(
            versionCheck = VersionCheckProperties(endpoint = "https://mirror.example/releases.json"),
            installationCheck = InstallationCheckProperties(endpoint = endpointed),
        )

        assertThat(none).isFalse()
        assertThat(allConfigured).isFalse()
    }

    @Test
    fun `exchange without an endpoint, or an earlier fetch by the hub, counts as in use`() {
        val off = VersionCheckProperties(enabled = false)
        val notShared = SharingProperties(installationId = false)

        assertThat(inUse(versionCheck = off, sharing = notShared, exchange = ExchangeProperties(enabled = true))).isTrue()
        assertThat(inUse(versionCheck = off, sharing = notShared, exchange = ExchangeProperties(enabled = true, endpoint = "https://exchange.internal"))).isFalse()
        assertThat(inUse(versionCheck = off, sharing = notShared, fetchedBefore = true)).isTrue()
    }
}
