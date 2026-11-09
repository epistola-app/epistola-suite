// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.privacy.TelemetryDetail
import app.epistola.suite.support.privacy.TenantIdentity
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/**
 * The connection to the Epistola support service (`docs/support-protocol.md`). Off by default:
 * with [enabled] false nothing is registered and nothing is sent.
 *
 * @property enabled register this installation with the support service and use the services
 *   Epistola grants it.
 * @property endpoint the support service's issuer URL. Blank: found through the product domain's
 *   discovery document (ADR 0030), and `https://support.{product-domain}` when that names none.
 * @property allowHttp permit a plaintext `http:` support service. Local development only: the
 *   installation's client assertions, tokens and uploads cross this connection. An `http:`
 *   [endpoint] without it stops startup.
 * @property autoRegister let the scheduled check register this installation on its own, and
 *   register it again at once when a registration expires before it is activated. Off by default:
 *   nothing leaves the installation until an installation administrator presses "Register" under
 *   Platform → Epistola support.
 * @property checkInterval how often one node refreshes the status (and registers, with [autoRegister]).
 * @property connectTimeout connect timeout for every call to the support service.
 * @property readTimeout the deadline for one whole call to the support service, from sending the
 *   request to the last byte of the answer (not per read); a request body adds a second per 512 KiB.
 * @property services the support services this installation uses (`telemetry`, `requests`,
 *   `mirror`, `usage`) until an installation administrator saves a selection under Platform → Epistola
 *   support; from then on the saved selection wins and this is ignored. Empty by default: nothing
 *   is used until someone chooses. A name this version does not know is kept and logged, never an
 *   error, so a configuration that names a retired or a newer service still starts.
 * @property tenantDefaults what tenants share with Epistola until a tenant chooses itself, until an
 *   installation administrator saves a preset under Platform → Epistola support ([TenantDefaults]).
 * @property privacy what Epistola sees of the tenants and how much telemetry carries, until an
 *   installation administrator saves them under Platform → Epistola support ([Privacy]).
 * @property usage how usage for billing is reported ([Usage]).
 */
@ConfigurationProperties(prefix = "epistola.support")
data class SupportProperties(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val allowHttp: Boolean = false,
    val autoRegister: Boolean = false,
    val checkInterval: Duration = Duration.ofMinutes(5),
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(30),
    val services: List<String> = emptyList(),
    val tenantDefaults: TenantDefaults = TenantDefaults(),
    val privacy: Privacy = Privacy(),
    val usage: Usage = Usage(),
) {
    init {
        require(endpoint.isBlank() || configuredIssuer != null) {
            "epistola.support.endpoint must be an absolute http(s) URL, such as https://support.epistola.app; got '$endpoint'"
        }
        require(allowHttp || configuredIssuer?.scheme != "http") {
            "epistola.support.endpoint '$endpoint' is plaintext http; use https, or set epistola.support.allow-http=true for a local support service"
        }
        require(!checkInterval.isNegative && !checkInterval.isZero) { "epistola.support.check-interval must be positive" }
        require(usage.backlog >= Duration.ofHours(1)) { "epistola.support.usage.backlog must be at least an hour" }
    }

    /**
     * `epistola.support.usage` (Helm `support.usage`).
     *
     * @property backlog how far back hours missed while the installation was down (or the support
     *   service unreachable) are still sent once it is back; 72 hours by default. Never further than
     *   the generation statistics keep hours (`epistola.statistics.hourly-retention`), as an hour no
     *   longer kept would read as empty. Hours older than that are not sent: the running totals of the
     *   next report carry what they held, so nothing is lost for billing.
     */
    data class Usage(
        val backlog: Duration = Duration.ofHours(72),
    )

    /**
     * A preset of what tenants share, from the deployment configuration (Helm
     * `support.tenantDefaults`). Applied at startup only while no installation administrator has
     * saved a preset, recorded as set by the configuration; it then applies to every tenant without
     * a choice of its own, and to new tenants, as consent given by whoever configured the
     * installation. Blank (the default): no preset, and every tenant sets itself up.
     *
     * @property requests support requests: `share` or `no`.
     * @property mirror `off`, `all` (every catalog) or `marked` (the catalogs a tenant marks).
     */
    data class TenantDefaults(
        val requests: String = "",
        val mirror: String = "",
    ) {
        init {
            require(requests.isBlank() || requests.trim().lowercase() in setOf("share", "no")) {
                "epistola.support.tenant-defaults.requests must be share or no; got '$requests'"
            }
            require(mirror.isBlank() || mirror.trim().lowercase() in setOf("off", "all", "marked")) {
                "epistola.support.tenant-defaults.mirror must be off, all or marked; got '$mirror'"
            }
        }

        /** True to share support requests, false not to, null when not configured. */
        val requestsShare: Boolean? get() = requests.trim().lowercase().takeIf { it.isNotEmpty() }?.let { it == "share" }

        /** The mirror preset in lower case, or null when not configured. */
        val mirrorChoice: String? get() = mirror.trim().lowercase().takeIf { it.isNotEmpty() }
    }

    /**
     * What Epistola sees of the tenants and how much telemetry carries (ADR 0052 §1–2), until an
     * installation administrator saves them under Platform → Epistola support. Helm
     * `support.privacy.tenantIdentity` / `support.privacy.telemetry`.
     *
     * @property tenantIdentity `confidential` (the default: every tenant as a reference) or `named`.
     * @property telemetry `pseudonymised` (the default: log messages without their values) or `full`.
     */
    data class Privacy(
        val tenantIdentity: String = "confidential",
        val telemetry: String = "pseudonymised",
    ) {
        init {
            require(TenantIdentity.parse(tenantIdentity) != null) { "epistola.support.privacy.tenant-identity must be confidential or named; got '$tenantIdentity'" }
            require(TelemetryDetail.parse(telemetry) != null) { "epistola.support.privacy.telemetry must be pseudonymised or full; got '$telemetry'" }
        }

        val tenantIdentityValue: TenantIdentity get() = TenantIdentity.parse(tenantIdentity)!!
        val telemetryValue: TelemetryDetail get() = TelemetryDetail.parse(telemetry)!!
    }

    /** [services], normalised: the selection in force until an administrator saves one. */
    val configuredServices: List<String> get() = SupportScope.normalize(services)

    /** Configured service names this version does not know; logged at startup, otherwise ignored. */
    val unknownServices: List<String> get() = configuredServices.filter { it !in SupportScope.NAMES }

    /** The configured issuer without a trailing slash; null when the support service is to be discovered. */
    val configuredIssuer: URI?
        get() = endpoint.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.let { value ->
            runCatching { URI(value) }.getOrNull()?.takeIf { (it.scheme == "https" || it.scheme == "http") && it.host != null && it.userInfo == null }
        }
}
