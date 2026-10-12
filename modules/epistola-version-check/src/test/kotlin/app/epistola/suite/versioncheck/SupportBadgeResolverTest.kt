// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import app.epistola.suite.versioncheck.SupportBadge.Kind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SupportBadgeResolverTest {
    private val id = UUID.fromString("01a0f4c2-c400-7abc-8def-0123456789ab")
    private val now = Instant.parse("2026-10-12T10:00:00Z")
    private val page = "https://epistola.app/installation/$id"

    private val supported = InstallationCheckResult(
        registered = true,
        status = "supported",
        supportedUntil = "2027-12-31",
        supportPartyName = "Acme Hosting",
        minVersion = "1.0.0",
    )

    private fun resolve(
        result: InstallationCheckResult? = supported,
        lastSuccessAt: Instant? = now.minus(Duration.ofHours(2)),
        sharesId: Boolean = true,
        sharesVersion: Boolean = true,
        version: String = "1.4.0",
    ) = SupportBadgeResolver.resolve(
        sharesInstallationId = sharesId,
        sharesVersion = sharesVersion,
        installationId = id,
        currentVersion = version,
        status = InstallationCheckStatus(lastAttemptAt = lastSuccessAt, lastSuccessAt = lastSuccessAt, result = result),
        now = now,
        staleAfter = Duration.ofDays(3),
    )

    @Test
    fun `a supported installation names its party and end date and links its page with the version`() {
        val badge = resolve()
        assertThat(badge.kind).isEqualTo(Kind.SUPPORTED)
        assertThat(badge.text).isEqualTo("Supported by Acme Hosting until 31 Dec 2027")
        assertThat(badge.href).isEqualTo("$page?version=1.4.0")
        assertThat(badge.tone).isEqualTo(SupportBadge.Tone.OK)
    }

    @Test
    fun `the version is left out of the link when it is not shared or not a release`() {
        assertThat(resolve(sharesVersion = false).href).isEqualTo(page)
        assertThat(resolve(version = "dev").href).isEqualTo(page)
        assertThat(resolve(version = "1.4.0+build.7").href).isEqualTo("$page?version=1.4.0")
    }

    @Test
    fun `a version below the installation's policy is called out`() {
        val badge = resolve(result = supported.copy(minVersion = "1.5.0"))
        assertThat(badge.kind).isEqualTo(Kind.VERSION_UNSUPPORTED)
        assertThat(badge.text).isEqualTo("Supported by Acme Hosting, but version 1.4.0 is out of support")
        assertThat(badge.tone).isEqualTo(SupportBadge.Tone.WARNING)
    }

    @Test
    fun `ended, revoked and unregistered installations link their page`() {
        assertThat(resolve(result = supported.copy(status = "expired")).kind).isEqualTo(Kind.ENDED)
        assertThat(resolve(result = supported.copy(status = "revoked")).text).isEqualTo("Not supported")
        val unregistered = resolve(result = InstallationCheckResult(registered = false))
        assertThat(unregistered.kind).isEqualTo(Kind.NOT_REGISTERED)
        assertThat(unregistered.text).isEqualTo("Not registered with Epistola")
        assertThat(unregistered.href).startsWith(page)
    }

    @Test
    fun `a contract that ended since the last check reads as ended`() {
        assertThat(resolve(result = supported.copy(supportedUntil = "2026-10-11")).kind).isEqualTo(Kind.ENDED)
        assertThat(resolve(result = supported.copy(supportedUntil = "2026-10-12")).kind).isEqualTo(Kind.SUPPORTED)
    }

    @Test
    fun `not sharing the id says so, links an explanation and sends nothing`() {
        val badge = resolve(sharesId = false)
        assertThat(badge.kind).isEqualTo(Kind.NOT_VERIFIABLE)
        assertThat(badge.text).isEqualTo("Support cannot be verified")
        assertThat(badge.href).isEqualTo("https://epistola.app/help/support-not-verifiable")
    }

    @Test
    fun `no answer yet, a failure or a stale answer is never read as unsupported`() {
        for (badge in listOf(
            resolve(result = null, lastSuccessAt = null),
            resolve(lastSuccessAt = now.minus(Duration.ofDays(4))),
            resolve(result = supported.copy(status = null)),
        )) {
            assertThat(badge.kind).isEqualTo(Kind.CHECK_UNAVAILABLE)
            assertThat(badge.text).isEqualTo("Support could not be checked")
            assertThat(badge.href).isEqualTo("https://epistola.app/help/support-check-unavailable")
        }
        assertThat(resolve(lastSuccessAt = now.minus(Duration.ofDays(2))).kind).isEqualTo(Kind.SUPPORTED)
    }
}
