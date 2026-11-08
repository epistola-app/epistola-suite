// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.privacy

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresAuthentication
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.support.SupportProperties
import app.epistola.suite.time.EpistolaClock
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What Epistola sees of this installation's tenants (ADR 0052 §1). */
enum class TenantIdentity {
    /** Tenant keys are sent as they are. */
    NAMED,

    /** Every tenant is sent as its [TenantReferences] reference. */
    CONFIDENTIAL,
    ;

    companion object {
        fun parse(v: String): TenantIdentity? = entries.firstOrNull { it.name.equals(v.trim(), ignoreCase = true) }
    }
}

/** How much telemetry carries (ADR 0052 §2). */
enum class TelemetryDetail {
    /** Log messages, exception messages and every tag as the suite writes them. */
    FULL,

    /** Log messages as written in the code without their values, exceptions without their message, no template tag. */
    PSEUDONYMISED,
    ;

    companion object {
        fun parse(v: String): TelemetryDetail? = entries.firstOrNull { it.name.equals(v.trim(), ignoreCase = true) }
    }
}

/**
 * The installation's privacy settings for Epistola support.
 *
 * @property fromConfiguration nobody saved them: they come from `epistola.support.privacy.*` (or its defaults).
 */
data class SupportPrivacySettings(
    val tenantIdentity: TenantIdentity,
    val telemetry: TelemetryDetail,
    val fromConfiguration: Boolean,
    val setBy: String? = null,
    val setAt: OffsetDateTime? = null,
) {
    val confidential: Boolean get() = tenantIdentity == TenantIdentity.CONFIDENTIAL
    val pseudonymised: Boolean get() = telemetry == TelemetryDetail.PSEUDONYMISED
}

/** As stored in `app_metadata` under [SupportPrivacy.KEY]. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StoredSupportPrivacy(
    val tenantIdentity: String = TenantIdentity.CONFIDENTIAL.name,
    val telemetry: String = TelemetryDetail.PSEUDONYMISED.name,
    val setBy: String? = null,
    val setAt: OffsetDateTime? = null,
)

/**
 * The privacy settings in force, cached for the hot paths that need them on every log line and every
 * export (they cannot dispatch through the mediator from a logging thread). Saved settings win over
 * the configuration; every node re-reads them every [REFRESH_SECONDS] seconds, the node that saves at once.
 */
@Component
class SupportPrivacy(
    private val metadata: AppMetadataService,
    private val properties: SupportProperties,
) {
    @Volatile private var cached: SupportPrivacySettings? = null

    fun current(): SupportPrivacySettings = cached ?: refresh()

    @Scheduled(fixedDelay = REFRESH_SECONDS, timeUnit = TimeUnit.SECONDS)
    fun refresh(): SupportPrivacySettings {
        val read = runCatching { metadata.getAs(KEY, StoredSupportPrivacy::class.java) }.getOrNull()
        val settings = if (read == null) {
            SupportPrivacySettings(properties.privacy.tenantIdentityValue, properties.privacy.telemetryValue, fromConfiguration = true)
        } else {
            SupportPrivacySettings(
                TenantIdentity.parse(read.tenantIdentity) ?: TenantIdentity.CONFIDENTIAL,
                TelemetryDetail.parse(read.telemetry) ?: TelemetryDetail.PSEUDONYMISED,
                fromConfiguration = false,
                setBy = read.setBy,
                setAt = read.setAt,
            )
        }
        cached = settings
        return settings
    }

    internal fun save(stored: StoredSupportPrivacy): SupportPrivacySettings {
        metadata.setAs(KEY, stored)
        return refresh()
    }

    companion object {
        const val KEY = "support.privacy"
        const val REFRESH_SECONDS = 30L
    }
}

/**
 * Tenant references (ADR 0052 §1): `t-` and the first 16 hex digits of an HMAC-SHA-256 of the tenant
 * key, under a secret this installation generates once and never sends. A plain hash would not do:
 * tenant keys are guessable. Every service that names a tenant sends this reference.
 */
@Component
class TenantReferences(
    private val metadata: AppMetadataService,
    private val privacy: SupportPrivacy,
) {
    @Volatile private var secret: ByteArray? = null

    /** References already computed: asked for on every forwarded log line. Bounded, as every cache here. */
    private val computed: Cache<String, String> = Caffeine.newBuilder().maximumSize(MAX_CACHED).build()

    /** [tenantKey]'s reference, whatever the setting. */
    fun reference(tenantKey: String): String = computed.get(tenantKey) {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret(), ALGORITHM))
        PREFIX + HexFormat.of().formatHex(mac.doFinal(it.toByteArray(Charsets.UTF_8)), 0, 8)
    }

    fun reference(tenantKey: TenantKey): String = reference(tenantKey.value)

    /** Tenants are confidential to Epistola now: names and addresses stay in the installation too. */
    fun confidential(): Boolean = privacy.current().confidential

    /** What is sent for [tenantKey]: the key itself when tenants are named, its reference when confidential. */
    fun onWire(tenantKey: String): String = if (privacy.current().confidential) reference(tenantKey) else tenantKey

    fun onWire(tenantKey: TenantKey): String = onWire(tenantKey.value)

    private fun secret(): ByteArray = secret ?: synchronized(this) {
        secret ?: run {
            val generated = ByteArray(32).also { SecureRandom().nextBytes(it) }
            metadata.setIfAbsent(SECRET_KEY, mapOf("key" to Base64.getEncoder().encodeToString(generated)))
            val stored = metadata.get(SECRET_KEY)?.get("key")?.asString() ?: error("The tenant reference secret could not be stored")
            Base64.getDecoder().decode(stored).also { secret = it }
        }
    }

    companion object {
        const val PREFIX = "t-"
        const val SECRET_KEY = "support.tenant-reference-secret"
        private const val MAX_CACHED = 10_000L
        private const val ALGORITHM = "HmacSHA256"
    }
}

/** The privacy settings, for the platform page. */
class GetSupportPrivacy :
    Query<SupportPrivacySettings>,
    RequiresAuthentication

@Component
class GetSupportPrivacyHandler(private val privacy: SupportPrivacy) : QueryHandler<GetSupportPrivacy, SupportPrivacySettings> {
    override fun handle(query: GetSupportPrivacy): SupportPrivacySettings = privacy.refresh()
}

/** Saves the privacy settings; every node applies them within [SupportPrivacy.REFRESH_SECONDS] seconds. */
data class SetSupportPrivacy(
    val tenantIdentity: TenantIdentity,
    val telemetry: TelemetryDetail,
) : Command<SupportPrivacySettings>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

@Component
class SetSupportPrivacyHandler(
    private val privacy: SupportPrivacy,
) : CommandHandler<SetSupportPrivacy, SupportPrivacySettings> {
    override fun handle(command: SetSupportPrivacy): SupportPrivacySettings = privacy.save(
        StoredSupportPrivacy(command.tenantIdentity.name, command.telemetry.name, SecurityContext.current().displayName, EpistolaClock.offsetDateTime()),
    )
}
