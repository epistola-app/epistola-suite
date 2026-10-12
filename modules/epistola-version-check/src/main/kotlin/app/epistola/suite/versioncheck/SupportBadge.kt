// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import app.epistola.suite.htmx.footer.FooterFragment
import app.epistola.suite.htmx.footer.FooterStatusContributor
import app.epistola.suite.installation.InstallationService
import app.epistola.suite.productdomain.ProductDomainProperties
import app.epistola.suite.time.EpistolaClock
import app.epistola.suite.version.SemVersion
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * The support badge in the footer bar. It is always shown and no setting hides it: whether support
 * can be verified must be visible to every user. Only its contents depend on what the installation
 * shares (docs/outbound-traffic.md).
 */
data class SupportBadge(
    val kind: Kind,
    val text: String,
    /** An `https:` link to the installation page or an explanation on epistola.app. */
    val href: String,
    val tone: Tone,
) {
    enum class Kind { SUPPORTED, VERSION_UNSUPPORTED, ENDED, REVOKED, NOT_REGISTERED, CHECK_UNAVAILABLE, NOT_VERIFIABLE }

    enum class Tone(val attribute: String) { OK("ok"), WARNING("warning"), NEUTRAL("neutral") }
}

object SupportBadgeResolver {
    /** The explanation pages, on the product domain's [site]; they receive no data. */
    fun helpNotVerifiable(site: String) = "$site/help/support-not-verifiable"

    fun helpCheckUnavailable(site: String) = "$site/help/support-check-unavailable"

    private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /**
     * Decides the badge. A failure or a deliberate choice not to share never reads as "not
     * supported": those get their own text and an explanation page that receives no data. Every
     * link points at [site], the product domain's site, so a staging Suite links to staging.
     */
    fun resolve(
        site: String,
        sharesInstallationId: Boolean,
        sharesVersion: Boolean,
        installationId: UUID,
        currentVersion: String,
        status: InstallationCheckStatus?,
        now: Instant,
        staleAfter: Duration,
    ): SupportBadge {
        if (!sharesInstallationId) {
            return SupportBadge(SupportBadge.Kind.NOT_VERIFIABLE, "Support cannot be verified", helpNotVerifiable(site), SupportBadge.Tone.NEUTRAL)
        }
        val fresh = status?.lastSuccessAt?.let { Duration.between(it, now) <= staleAfter } == true
        val result = status?.result?.takeIf { fresh }
            ?: return SupportBadge(SupportBadge.Kind.CHECK_UNAVAILABLE, "Support could not be checked", helpCheckUnavailable(site), SupportBadge.Tone.NEUTRAL)

        val running = SemVersion.parse(currentVersion)
        val page = buildString {
            append("$site/installation/$installationId")
            if (sharesVersion && running != null) append("?version=").append(currentVersion.substringBefore('+'))
        }
        if (!result.registered) {
            return SupportBadge(SupportBadge.Kind.NOT_REGISTERED, "Not registered with Epistola", page, SupportBadge.Tone.NEUTRAL)
        }

        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        val until = result.supportedUntil?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        // The cached answer can be up to [staleAfter] old: a contract that ended since still ends.
        val status = if (result.status == "supported" && until != null && until < today) "expired" else result.status
        val party = result.supportPartyName
        return when (status) {
            "supported" -> {
                val floor = result.minVersion?.let { SemVersion.parse(it) }
                if (running != null && floor != null && running < floor) {
                    SupportBadge(
                        SupportBadge.Kind.VERSION_UNSUPPORTED,
                        (party?.let { "Supported by $it, but" } ?: "Supported, but") + " version $currentVersion is out of support",
                        page,
                        SupportBadge.Tone.WARNING,
                    )
                } else {
                    val text = (party?.let { "Supported by $it" } ?: "Supported") + (until?.let { " until ${DATE.format(it)}" } ?: "")
                    SupportBadge(SupportBadge.Kind.SUPPORTED, text, page, SupportBadge.Tone.OK)
                }
            }

            "expired" -> SupportBadge(SupportBadge.Kind.ENDED, "Support ended", page, SupportBadge.Tone.WARNING)

            "revoked" -> SupportBadge(SupportBadge.Kind.REVOKED, "Not supported", page, SupportBadge.Tone.WARNING)

            else -> SupportBadge(SupportBadge.Kind.CHECK_UNAVAILABLE, "Support could not be checked", helpCheckUnavailable(site), SupportBadge.Tone.NEUTRAL)
        }
    }
}

/**
 * Serves the badge to the footer (`${@supportBadgeService.badge()}`). It renders on every page, so
 * the cached status is read from the database at most once a minute per node.
 */
@Component("supportBadgeService")
class SupportBadgeService(
    private val installationCheck: InstallationCheckService,
    private val installationService: InstallationService,
    private val sharing: SharingProperties,
    private val properties: InstallationCheckProperties,
    private val productDomain: ProductDomainProperties,
) {
    @Volatile
    private var cached: Pair<Instant, SupportBadge>? = null

    fun badge(): SupportBadge {
        val now = EpistolaClock.instant()
        cached?.let { (at, badge) -> if (Duration.between(at, now) < CACHE_FOR) return badge }
        val badge = runCatching {
            SupportBadgeResolver.resolve(
                site = productDomain.siteUrl,
                sharesInstallationId = sharing.installationId,
                sharesVersion = sharing.suiteVersion,
                installationId = installationService.get().id,
                currentVersion = installationCheck.currentVersion,
                status = installationCheck.status(),
                now = now,
                staleAfter = properties.staleAfter,
            )
        }.getOrElse {
            // A broken badge must never break the page it renders on.
            SupportBadge(SupportBadge.Kind.CHECK_UNAVAILABLE, "Support could not be checked", SupportBadgeResolver.helpCheckUnavailable(productDomain.siteUrl), SupportBadge.Tone.NEUTRAL)
        }
        cached = now to badge
        return badge
    }

    /** Drops the cache, e.g. right after a check ran. */
    fun invalidate() {
        cached = null
    }

    companion object {
        private val CACHE_FOR: Duration = Duration.ofMinutes(1)
    }
}

@Component
class SupportBadgeFooterContributor : FooterStatusContributor {
    override fun fragments(): List<FooterFragment> = listOf(FooterFragment(template = "versioncheck/support-badge", fragment = "badge"))
}
