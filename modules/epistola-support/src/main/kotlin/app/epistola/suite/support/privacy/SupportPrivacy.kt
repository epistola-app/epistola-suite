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
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.support.SupportProperties
import app.epistola.suite.time.EpistolaClock
import app.epistola.suite.validation.validate
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What Epistola sees of this installation's tenants (ADR 0052 §1). */
enum class TenantIdentity {
    /** Tenant keys are sent as they are. */
    NAMED,

    /** Every tenant is sent as its [TenantReferences] reference; the catalog mirror is unavailable. */
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
 * @property fromConfiguration nobody saved them: they come from `epistola.support.privacy.*` (or its
 *   defaults), or for another connection from its entry in `epistola.support.connections`.
 * @property followsInstallation for a connection other than Epistola support: nothing is set for it,
 *   so the installation's settings apply.
 */
data class SupportPrivacySettings(
    val tenantIdentity: TenantIdentity,
    val telemetry: TelemetryDetail,
    val fromConfiguration: Boolean,
    val setBy: String? = null,
    val setAt: OffsetDateTime? = null,
    val followsInstallation: Boolean = false,
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
 *
 * Every support connection has its own (ADR 0053 §1): Epistola support's are the installation's
 * ([current]); another connection's ([forConnection]) are what an administrator saved for it, else its
 * entry in `epistola.support.connections`, else the installation's.
 */
@Component
class SupportPrivacy(
    private val metadata: AppMetadataService,
    private val properties: SupportProperties,
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) {
    @Volatile private var cached: SupportPrivacySettings? = null

    /** Every connection's settings, as last read; a connection not in it follows [current]. */
    @Volatile private var perConnection: Map<UUID, SupportPrivacySettings> = emptyMap()

    /**
     * [connectionId]'s settings, without touching the database: what an administrator saved for it,
     * else its configuration entry's, else the installation's ([current]), which a connection read
     * nowhere yet follows too, the strictest before the first read.
     */
    fun forConnection(connectionId: UUID): SupportPrivacySettings = perConnection[connectionId] ?: current().copy(followsInstallation = true)

    /**
     * The settings in force, without touching the database: it is asked on every forwarded log line,
     * on whatever thread logged. Before the first read ([refresh], scheduled from startup) the
     * strictest settings apply.
     */
    fun current(): SupportPrivacySettings = cached ?: STRICTEST

    /**
     * Reads the settings: saved ones win over the configuration. A read that fails keeps the settings
     * last read, or the strictest before any: a database that cannot be reached never loosens them.
     */
    @Scheduled(fixedDelay = REFRESH_SECONDS, timeUnit = TimeUnit.SECONDS)
    fun refresh(): SupportPrivacySettings = read() ?: cached ?: STRICTEST

    /**
     * The settings as stored now, or null when they cannot be read. For a decision that must not rest
     * on a stand-in, such as forgetting every tenant's choice at startup: [current] and [refresh] give
     * the strictest settings while nothing was read, which would read as "confidential".
     */
    fun read(): SupportPrivacySettings? {
        val read = try {
            metadata.getAs(KEY, StoredSupportPrivacy::class.java)
        } catch (e: RuntimeException) {
            log.warn("Support privacy settings could not be read; keeping the settings last read: {}", e.message)
            return null
        }
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
        readConnections(settings)?.let { perConnection = it }
        return settings
    }

    /** Every connection's settings given the [installation]'s; null when they cannot be read, which keeps the last. */
    private fun readConnections(installation: SupportPrivacySettings): Map<UUID, SupportPrivacySettings>? = try {
        jdbi.withHandle<Map<UUID, SupportPrivacySettings>, Exception> { handle ->
            handle.createQuery("SELECT id, endpoint, privacy::text AS privacy FROM support_connections")
                .map { rs, _ ->
                    val id = rs.getObject("id", UUID::class.java)
                    val saved = rs.getString("privacy")?.let { objectMapper.readValue(it, StoredSupportPrivacy::class.java) }
                    val configured = properties.connections.firstOrNull { it.baseUrl == rs.getString("endpoint")?.trimEnd('/') }
                    id to when {
                        saved != null -> SupportPrivacySettings(
                            TenantIdentity.parse(saved.tenantIdentity) ?: TenantIdentity.CONFIDENTIAL,
                            TelemetryDetail.parse(saved.telemetry) ?: TelemetryDetail.PSEUDONYMISED,
                            fromConfiguration = false,
                            setBy = saved.setBy,
                            setAt = saved.setAt,
                        )

                        configured != null && (configured.tenantIdentity.isNotBlank() || configured.telemetry.isNotBlank()) -> SupportPrivacySettings(
                            TenantIdentity.parse(configured.tenantIdentity) ?: installation.tenantIdentity,
                            TelemetryDetail.parse(configured.telemetry) ?: installation.telemetry,
                            fromConfiguration = true,
                        )

                        else -> installation.copy(followsInstallation = true)
                    }
                }
                .list()
                .toMap()
        }
    } catch (e: RuntimeException) {
        log.warn("Support connection privacy settings could not be read; keeping the settings last read: {}", e.message)
        null
    }

    /** Every connection whose settings this node knows. */
    internal fun connectionIds(): Set<UUID> = perConnection.keys

    internal fun save(stored: StoredSupportPrivacy): SupportPrivacySettings {
        metadata.setAs(KEY, stored)
        return refresh()
    }

    /** Saves [connectionId]'s own settings, no longer following the installation's, and applies them on this node at once. */
    internal fun saveFor(connectionId: UUID, stored: StoredSupportPrivacy): SupportPrivacySettings {
        val updated = jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate("UPDATE support_connections SET privacy = :value::jsonb, version = version + 1, updated_at = NOW() WHERE id = :id")
                .bind("id", connectionId).bind("value", objectMapper.writeValueAsString(stored)).execute()
        }
        if (updated == 0) throw SupportConnectionNotFoundException(connectionId)
        refresh()
        return forConnection(connectionId)
    }

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val KEY = "support.privacy"
        const val REFRESH_SECONDS = 30L

        /** Before the settings were read, and while they cannot be: confidential, pseudonymised. */
        val STRICTEST = SupportPrivacySettings(TenantIdentity.CONFIDENTIAL, TelemetryDetail.PSEUDONYMISED, fromConfiguration = false)
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
    fun reference(tenantKey: String): String = computed.get(tenantKey) { derive(PREFIX, it) }

    fun reference(tenantKey: TenantKey): String = reference(tenantKey.value)

    /**
     * [tenantKey]'s reference, or null while this node has not loaded the secret yet. Never touches the
     * database: for the telemetry legs, asked on whatever thread logged. The secret is loaded at startup.
     */
    fun referenceIfReady(tenantKey: String): String? = if (secret == null) null else reference(tenantKey)

    /** Loads the secret before the first forwarded log line needs it; a failure leaves it to the next use. */
    @EventListener(ApplicationReadyEvent::class)
    fun loadSecret() {
        try {
            secret()
        } catch (e: RuntimeException) {
            log.warn("The tenant reference secret could not be loaded yet; telemetry sends no tenant until it is: {}", e.message)
        }
    }

    /**
     * [userId]'s person reference (ADR 0053 §3): `p-` and 16 hex digits of an HMAC under the same
     * secret, over an input no tenant key can have, so it never equals a tenant's. Whoever handles a
     * request can tell two requests of one person apart without knowing who it is.
     */
    fun personReference(userId: java.util.UUID): String = computed.get(PERSON_INPUT + userId) { derive(PERSON_PREFIX, it) }

    /** [prefix] and the first 16 hex digits of the HMAC of [input] under the installation's secret: every reference. */
    private fun derive(prefix: String, input: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret(), ALGORITHM))
        return prefix + HexFormat.of().formatHex(mac.doFinal(input.toByteArray(Charsets.UTF_8)), 0, 8)
    }

    /** Tenants are confidential to Epistola now: names and addresses stay in the installation too. */
    fun confidential(): Boolean = privacy.current().confidential

    /** What is sent for [tenantKey]: the key itself when tenants are named, its reference when confidential. */
    fun onWire(tenantKey: String): String = if (privacy.current().confidential) reference(tenantKey) else tenantKey

    fun onWire(tenantKey: TenantKey): String = onWire(tenantKey.value)

    /** Tenants are confidential to [connectionId] now. */
    fun confidential(connectionId: UUID): Boolean = privacy.forConnection(connectionId).confidential

    /** What is sent to [connectionId] for [tenantKey]: its key when tenants are named to it, its reference when confidential. */
    fun onWire(tenantKey: String, connectionId: UUID): String = if (confidential(connectionId)) reference(tenantKey) else tenantKey

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
        private val log = LoggerFactory.getLogger(TenantReferences::class.java)
        const val PERSON_PREFIX = "p-"

        /** Prefixed to a user id before the HMAC: a tenant key never contains a space. */
        private const val PERSON_INPUT = "person "
        const val SECRET_KEY = "support.tenant-reference-secret"
        private const val MAX_CACHED = 10_000L
        private const val ALGORITHM = "HmacSHA256"
    }
}

/** The privacy settings, for the platform page. */
class GetSupportPrivacy :
    Query<SupportPrivacySettings>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

@Component
class GetSupportPrivacyHandler(private val privacy: SupportPrivacy) : QueryHandler<GetSupportPrivacy, SupportPrivacySettings> {
    override fun handle(query: GetSupportPrivacy): SupportPrivacySettings = privacy.refresh()
}

/** Something that has to happen when tenants become confidential to a connection (the catalog mirror withdraws from it). */
interface TenantIdentityListener {
    fun becameConfidential(connectionId: UUID)
}

/**
 * Saves the privacy settings; every node applies them within [SupportPrivacy.REFRESH_SECONDS] seconds.
 * Making tenants confidential tells every [TenantIdentityListener] at once, for Epistola support's
 * connection and every connection that follows the installation's settings, so the catalog mirror
 * withdraws what it shares there: a release names its client whatever key it is filed under.
 */
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
    private val listeners: ObjectProvider<TenantIdentityListener>,
) : CommandHandler<SetSupportPrivacy, SupportPrivacySettings> {
    override fun handle(command: SetSupportPrivacy): SupportPrivacySettings {
        val before = privacy.refresh()
        val connectionsBefore = privacy.connectionIds().associateWith { privacy.forConnection(it) }
        val saved = privacy.save(
            StoredSupportPrivacy(command.tenantIdentity.name, command.telemetry.name, SecurityContext.current().displayName, EpistolaClock.offsetDateTime()),
        )
        // The installation's settings are those of every connection that follows them.
        connectionsBefore.forEach { (connectionId, was) ->
            if (was.followsInstallation && privacy.forConnection(connectionId).confidential && (!was.confidential || before.fromConfiguration)) {
                listeners.orderedStream().forEach { it.becameConfidential(connectionId) }
            }
        }
        return saved
    }
}

/** No support connection has [connectionId]. */
class SupportConnectionNotFoundException(val connectionId: UUID) : RuntimeException("No support connection $connectionId")

/**
 * Saves the privacy of support connection [connectionId], another than Epistola support's (ADR 0053
 * §1): what it learns of tenants and how much telemetry carries over it. Every node applies it within
 * [SupportPrivacy.REFRESH_SECONDS] seconds.
 */
data class SetSupportConnectionPrivacy(
    val connectionId: UUID,
    val tenantIdentity: TenantIdentity,
    val telemetry: TelemetryDetail,
) : Command<SupportPrivacySettings>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

@Component
class SetSupportConnectionPrivacyHandler(
    private val privacy: SupportPrivacy,
    private val listeners: ObjectProvider<TenantIdentityListener>,
) : CommandHandler<SetSupportConnectionPrivacy, SupportPrivacySettings> {
    override fun handle(command: SetSupportConnectionPrivacy): SupportPrivacySettings {
        privacy.refresh()
        val before = privacy.forConnection(command.connectionId)
        val saved = privacy.saveFor(
            command.connectionId,
            StoredSupportPrivacy(command.tenantIdentity.name, command.telemetry.name, SecurityContext.current().displayName, EpistolaClock.offsetDateTime()),
        )
        // Tenants became confidential to this connection: what it was sent of them is withdrawn.
        if (saved.confidential && (!before.confidential || before.fromConfiguration)) listeners.orderedStream().forEach { it.becameConfidential(command.connectionId) }
        return saved
    }
}
