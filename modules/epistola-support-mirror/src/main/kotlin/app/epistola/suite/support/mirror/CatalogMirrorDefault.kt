// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.EventHandler
import app.epistola.suite.mediator.EventPhase
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresAuthentication
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.support.SupportProperties
import app.epistola.suite.support.connection.SupportConnections
import app.epistola.suite.support.privacy.SupportPrivacy
import app.epistola.suite.support.sharing.SharingRestrictions
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/** What the installation's default does for a tenant without a choice of its own. */
enum class CatalogMirrorDefaultChoice(val label: String) {
    /** Tenants do not share catalogs unless they opt in themselves. */
    OFF("Do not share"),

    /** Tenants share every catalog without a policy of its own, new ones included. */
    ALL("Share every catalog"),

    /** Tenants share only the catalogs they mark to share. */
    MARKED("Share only the catalogs a tenant marks"),
}

/**
 * The installation's default for the catalog mirror (`support_sharing_default`, section `mirror`): it applies directly
 * to every tenant without a choice of its own, written into that tenant's consent record as agreed or
 * stopped by the installation administrator who set it ([CatalogMirrorConsent.followsDefault]). A
 * tenant's own choice wins until it follows the default again ([FollowCatalogMirrorDefault]).
 *
 * @property consentVersion the statement of what is shared the administrator agreed to; null when never set.
 * @property fromConfiguration it came from the deployment configuration
 *   (`epistola.support.tenant-defaults.mirror`), not from an installation administrator.
 */
data class CatalogMirrorDefault(
    val choice: CatalogMirrorDefaultChoice,
    val consentVersion: String?,
    val setAt: OffsetDateTime?,
    val setBy: String?,
    val fromConfiguration: Boolean = false,
) {
    /** A preset exists: an installation administrator saved one, or the configuration provided it. */
    val isSet: Boolean get() = setAt != null

    /** Every choice, for the form. */
    val choices: List<CatalogMirrorDefaultChoice> get() = CatalogMirrorDefaultChoice.entries

    companion object {
        val NONE = CatalogMirrorDefault(CatalogMirrorDefaultChoice.OFF, null, null, null)
    }
}

/** The installation's default for the catalog mirror, for the platform page and every tenant's page. */
data class GetCatalogMirrorDefault(val connectionId: UUID) :
    Query<CatalogMirrorDefault>,
    RequiresAuthentication

@Component
class GetCatalogMirrorDefaultHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetCatalogMirrorDefault, CatalogMirrorDefault> {
    override fun handle(query: GetCatalogMirrorDefault): CatalogMirrorDefault = jdbi.withHandle<CatalogMirrorDefault, Exception> { readMirrorDefault(it, query.connectionId) }
}

/** What setting the default did: the default as it now stands, and how many tenants it changed. */
data class CatalogMirrorDefaultResult(val default: CatalogMirrorDefault, val tenantsChanged: Int)

/**
 * Sets the installation's default for the catalog mirror and applies it at once to every tenant that
 * follows it: sharing opts them in with that choice, agreed to the current statement
 * ([CatalogMirror.CONSENT_VERSION]); not sharing opts them out, and the reconcile withdraws what the
 * mirror holds of them, exactly as their own opt-out would. Tenants with a choice of their own are
 * left alone.
 */
data class SetCatalogMirrorDefault(
    val choice: CatalogMirrorDefaultChoice,
    val connectionId: UUID,
) : Command<CatalogMirrorDefaultResult>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

@Component
class SetCatalogMirrorDefaultHandler(
    private val jdbi: Jdbi,
    private val privacy: SupportPrivacy,
) : CommandHandler<SetCatalogMirrorDefault, CatalogMirrorDefaultResult> {
    override fun handle(command: SetCatalogMirrorDefault): CatalogMirrorDefaultResult {
        if (command.choice != CatalogMirrorDefaultChoice.OFF && privacy.forConnection(command.connectionId).confidential) throw CatalogMirrorConfidentialException()
        var stopped = emptyList<TenantKey>()
        val result = jdbi.withHandle<CatalogMirrorDefaultResult, Exception> { handle ->
            val outcome = setMirrorDefault(handle, command.connectionId, command.choice, SecurityContext.current().userId.value, fromConfiguration = false)
            stopped = outcome.stopped
            CatalogMirrorDefaultResult(readMirrorDefault(handle, command.connectionId), outcome.changed)
        }
        // Withdraw at once rather than at the next round, as a tenant's own opt-out does.
        stopped.forEach { scheduleSync(jdbi, command.connectionId, it) }
        return result
    }
}

/**
 * Removes the installation preset: every tenant that followed it stops sharing, exactly as by its own
 * opt-out (withdrawn at the next reconcile), and has to set itself up.
 */
data class ClearCatalogMirrorDefault(val connectionId: UUID) :
    Command<CatalogMirrorDefaultResult>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

@Component
class ClearCatalogMirrorDefaultHandler(
    private val jdbi: Jdbi,
) : CommandHandler<ClearCatalogMirrorDefault, CatalogMirrorDefaultResult> {
    override fun handle(command: ClearCatalogMirrorDefault): CatalogMirrorDefaultResult {
        val stopped = jdbi.withHandle<List<TenantKey>, Exception> { handle ->
            optOutFollowers(handle, command.connectionId, EpistolaClock.offsetDateTime(), SecurityContext.current().userId.value).also {
                handle.createUpdate("DELETE FROM support_sharing_default WHERE ${mirrorSharing()}").bind("connection", command.connectionId).execute()
            }
        }
        stopped.forEach { scheduleSync(jdbi, command.connectionId, it) }
        return CatalogMirrorDefaultResult(CatalogMirrorDefault.NONE, stopped.size)
    }
}

/** What storing the default did: how many tenants it changed, and those that stopped sharing. */
internal class MirrorDefaultOutcome(val changed: Int, val stopped: List<TenantKey>)

/**
 * Stores the default as set by [userId] (null: by the configuration) and applies it to every tenant
 * that follows it: an opt-in with its choice, agreed to the current statement, or an opt-out.
 */
internal fun setMirrorDefault(handle: Handle, connectionId: UUID, choice: CatalogMirrorDefaultChoice, userId: UUID?, fromConfiguration: Boolean): MirrorDefaultOutcome {
    val now = EpistolaClock.offsetDateTime()
    val share = choice != CatalogMirrorDefaultChoice.OFF
    if (share) SharingRestrictions.requireAllowed(handle, CatalogMirror.SECTION, connectionId)
    val shareByDefault = choice == CatalogMirrorDefaultChoice.ALL
    handle.createUpdate(
        """
        INSERT INTO support_sharing_default (connection_id, section, share, options, statement_version, set_at, set_by, from_configuration)
        VALUES (:connection, '${CatalogMirror.SECTION}', :share, jsonb_build_object('shareByDefault', :shareByDefault), :version, :now,
                (SELECT id FROM users WHERE id = :userId), :fromConfiguration)
        ON CONFLICT (connection_id, section) DO UPDATE SET
            share = EXCLUDED.share, options = EXCLUDED.options, statement_version = EXCLUDED.statement_version,
            set_at = EXCLUDED.set_at, set_by = EXCLUDED.set_by, from_configuration = EXCLUDED.from_configuration
        """,
    )
        .bind("connection", connectionId)
        .bind("share", share)
        .bind("shareByDefault", shareByDefault)
        .bind("version", CatalogMirror.CONSENT_VERSION)
        .bind("now", now)
        .bind("userId", userId)
        .bind("fromConfiguration", fromConfiguration)
        .execute()
    if (!share) {
        val stopped = optOutFollowers(handle, connectionId, now, userId)
        return MirrorDefaultOutcome(stopped.size, stopped)
    }
    return MirrorDefaultOutcome(optInFollowers(handle, connectionId, shareByDefault, now, userId), emptyList())
}

/** Opts in every tenant without a record, and every follower, with the default's choice. */
private fun optInFollowers(handle: Handle, connectionId: UUID, shareByDefault: Boolean, now: OffsetDateTime, userId: UUID?): Int {
    val added = handle.createUpdate(
        """
        INSERT INTO support_sharing_choice (connection_id, section, tenant_key, options, statement_version, given_at, given_by, follows_default)
        SELECT :connection, '${CatalogMirror.SECTION}', t.id, jsonb_build_object('shareByDefault', :shareByDefault), :version, :now,
               (SELECT id FROM users WHERE id = :userId), TRUE
        FROM tenants t
        WHERE NOT EXISTS (SELECT 1 FROM support_sharing_choice m WHERE ${mirrorSharing("m")} AND m.tenant_key = t.id)
        """,
    )
        .bind("connection", connectionId)
        .bind("shareByDefault", shareByDefault)
        .bind("version", CatalogMirror.CONSENT_VERSION)
        .bind("now", now)
        .bind("userId", userId)
        .execute()
    val updated = handle.createUpdate(
        """
        UPDATE support_sharing_choice
        SET options = jsonb_build_object('shareByDefault', :shareByDefault), statement_version = :version, given_at = :now,
            given_by = (SELECT id FROM users WHERE id = :userId),
            withdrawn_at = NULL, withdrawn_by = NULL, withdrawal_confirmed_at = NULL
        WHERE ${mirrorSharing()} AND follows_default
          AND (withdrawn_at IS NOT NULL OR ${shareByDefault()} IS DISTINCT FROM :shareByDefault OR statement_version IS DISTINCT FROM :version)
        """,
    )
        .bind("connection", connectionId)
        .bind("shareByDefault", shareByDefault)
        .bind("version", CatalogMirror.CONSENT_VERSION)
        .bind("now", now)
        .bind("userId", userId)
        .execute()
    return added + updated
}

/** Opts out every follower that shares; the reconcile confirms each withdrawal. */
private fun optOutFollowers(handle: Handle, connectionId: UUID, now: OffsetDateTime, userId: UUID?): List<TenantKey> = handle.createQuery(
    """
    UPDATE support_sharing_choice
    SET withdrawn_at = :now, withdrawn_by = (SELECT id FROM users WHERE id = :userId), withdrawal_confirmed_at = NULL
    WHERE ${mirrorSharing()} AND follows_default AND withdrawn_at IS NULL
    RETURNING tenant_key
    """,
)
    .bind("connection", connectionId)
    .bind("now", now)
    .bind("userId", userId)
    .mapTo(String::class.java)
    .list()
    .map(TenantKey::of)

/**
 * Applies the preset of the deployment configuration (`epistola.support.tenant-defaults.mirror`) at
 * startup, only while no default is stored: once an installation administrator saved one, that wins.
 * Idempotent across nodes: only the node that stores it applies it. Nothing follows a default before
 * the first one is stored, so applying it never opts a tenant out.
 */
@Component
class ApplyConfiguredCatalogMirrorDefault(
    private val jdbi: Jdbi,
    private val properties: SupportProperties,
    private val privacy: SupportPrivacy,
    private val connections: SupportConnections,
) {
    /**
     * For the connection `epistola.support.*` configures (`epistola.support.tenant-defaults`) and every
     * entry of `epistola.support.connections` (its `tenant-defaults`), each applied once per configured
     * value, and not to a connection tenants are confidential to.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun apply() {
        privacy.read() ?: return
        if (properties.enabled) {
            choiceOf(properties.tenantDefaults)?.let { choice -> connections.configuredId()?.let { applyFor(it, choice, "$APPLIED_KEY.$it") } }
        }
        properties.connections.forEach { entry ->
            val choice = choiceOf(entry.tenantDefaults) ?: return@forEach
            connections.findByEndpoint(entry.baseUrl)?.id?.let { applyFor(it, choice, "$APPLIED_KEY.$it") }
        }
    }

    private fun choiceOf(defaults: SupportProperties.TenantDefaults): CatalogMirrorDefaultChoice? = when (defaults.mirrorChoice ?: return null) {
        "all" -> CatalogMirrorDefaultChoice.ALL
        "marked" -> CatalogMirrorDefaultChoice.MARKED
        else -> CatalogMirrorDefaultChoice.OFF
    }

    private fun applyFor(connectionId: java.util.UUID, choice: CatalogMirrorDefaultChoice, appliedKey: String) {
        if (privacy.forConnection(connectionId).confidential) return
        jdbi.inTransaction<Unit, Exception> { handle ->
            // Applied once per configured value: a preset an administrator cleared, or that a disconnect
            // forgot, does not come back at the next start.
            val applied = handle.createQuery("SELECT value ->> 'mirror' FROM app_metadata WHERE key = :key")
                .bind("key", appliedKey).mapTo(String::class.java).findOne().orElse(null)
            if (applied == choice.name) return@inTransaction
            handle.createUpdate(
                "INSERT INTO app_metadata (key, value, updated_at) VALUES (:key, jsonb_build_object('mirror', CAST(:choice AS TEXT)), NOW()) " +
                    "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()",
            ).bind("key", appliedKey).bind("choice", choice.name).execute()
            // Not allowed wins over a configured default: nothing is stored or applied.
            if (SharingRestrictions.isNotAllowed(handle, CatalogMirror.SECTION, connectionId)) return@inTransaction
            val stored = handle.createUpdate(
                """
                INSERT INTO support_sharing_default (connection_id, section, share, statement_version, set_at, from_configuration)
                VALUES (:connection, '${CatalogMirror.SECTION}', FALSE, :version, :now, TRUE) ON CONFLICT (connection_id, section) DO NOTHING
                """,
            )
                .bind("connection", connectionId)
                .bind("version", CatalogMirror.CONSENT_VERSION)
                .bind("now", EpistolaClock.offsetDateTime())
                .execute()
            // A preset the configuration stored and nobody replaced follows the configuration when it
            // changes (this value was not applied yet); one an administrator saved wins over it.
            val fromConfiguration = stored == 0 && handle.createQuery(
                "SELECT from_configuration FROM support_sharing_default WHERE connection_id = :connection AND section = '${CatalogMirror.SECTION}'",
            ).bind("connection", connectionId).mapTo(Boolean::class.java).findOne().orElse(false)
            if (stored == 1 || fromConfiguration) setMirrorDefault(handle, connectionId, choice, userId = null, fromConfiguration = true)
        }
    }

    companion object {
        /** The configured preset last applied per connection (`app_metadata`, `<key>.<connection id>`), so it is applied once per value and connection. */
        const val APPLIED_KEY = "support.mirror.configured-default-applied"
    }
}

/**
 * The tenant drops its own choice and follows the installation default again, which applies at once:
 * an opt-in with the default's choice when it shares, an opt-out (withdrawn at the next reconcile)
 * when it does not.
 */
data class FollowCatalogMirrorDefault(
    override val tenantKey: TenantKey,
    val connectionId: UUID,
) : Command<CatalogMirrorDefault>,
    RequiresPermission {
    override val permission = Permission.INTEGRATIONS_MANAGE
}

@Component
class FollowCatalogMirrorDefaultHandler(
    private val jdbi: Jdbi,
) : CommandHandler<FollowCatalogMirrorDefault, CatalogMirrorDefault> {
    override fun handle(command: FollowCatalogMirrorDefault): CatalogMirrorDefault {
        val stopped = jdbi.withHandle<Boolean, Exception> { applyMirrorDefault(it, command.connectionId, command.tenantKey) }
        if (stopped) scheduleSync(jdbi, command.connectionId, command.tenantKey)
        return jdbi.withHandle<CatalogMirrorDefault, Exception> { readMirrorDefault(it, command.connectionId) }
    }
}

/**
 * A new tenant follows the installation default of every connection that has one from the start, in
 * the transaction that creates it, so its catalogs are shared from the first round when it says so.
 */
@Component
class ApplyCatalogMirrorDefaultToNewTenant(
    private val jdbi: Jdbi,
) : EventHandler<CreateTenant> {
    override val phase = EventPhase.IMMEDIATE

    override fun on(event: CreateTenant, result: Any?) {
        jdbi.useHandle<Exception> { handle ->
            handle.createQuery("SELECT connection_id FROM support_sharing_default WHERE section = '${CatalogMirror.SECTION}' ORDER BY connection_id")
                .mapTo(UUID::class.java).list()
                .forEach { connectionId -> applyMirrorDefault(handle, connectionId, event.id) }
        }
    }
}

/**
 * Makes [tenantKey] follow the default as it stands now, agreed or stopped by the administrator who
 * set it.
 *
 * @return true when it stopped sharing, so a withdrawal is due.
 */
private fun applyMirrorDefault(handle: Handle, connectionId: UUID, tenantKey: TenantKey): Boolean {
    val default = handle.createQuery("SELECT share, COALESCE(${shareByDefault()}, FALSE) AS share_by_default, statement_version, set_by FROM support_sharing_default WHERE ${mirrorSharing()}")
        .bind("connection", connectionId)
        .map { rs, _ -> MirrorDefaultRow(rs.getBoolean("share"), rs.getBoolean("share_by_default"), rs.getString("statement_version"), rs.getObject("set_by", UUID::class.java)) }
        .findOne()
        .orElse(null)
    val now = EpistolaClock.offsetDateTime()
    if (default != null && default.share) {
        handle.createUpdate(
            """
            INSERT INTO support_sharing_choice (connection_id, section, tenant_key, options, statement_version, given_at, given_by, follows_default)
            VALUES (:connection, '${CatalogMirror.SECTION}', :t, jsonb_build_object('shareByDefault', :shareByDefault), :version, :now,
                    (SELECT id FROM users WHERE id = :by), TRUE)
            ON CONFLICT (connection_id, section, tenant_key) DO UPDATE SET
                follows_default = TRUE, options = EXCLUDED.options, statement_version = EXCLUDED.statement_version,
                given_at = EXCLUDED.given_at, given_by = EXCLUDED.given_by,
                withdrawn_at = NULL, withdrawn_by = NULL, withdrawal_confirmed_at = NULL
            """,
        )
            .bind("connection", connectionId)
            .bind("t", tenantKey.value)
            .bind("shareByDefault", default.shareByDefault)
            .bind("version", default.consentVersion)
            .bind("now", now)
            .bind("by", default.setBy)
            .execute()
        return false
    }
    return handle.createQuery(
        """
        UPDATE support_sharing_choice
        SET follows_default = TRUE,
            withdrawal_confirmed_at = CASE WHEN withdrawn_at IS NULL THEN NULL ELSE withdrawal_confirmed_at END,
            withdrawn_by = CASE WHEN withdrawn_at IS NULL THEN CAST(:by AS UUID) ELSE withdrawn_by END,
            withdrawn_at = COALESCE(withdrawn_at, :now)
        WHERE ${mirrorSharing()} AND tenant_key = :t
        RETURNING withdrawal_confirmed_at IS NULL
        """,
    )
        .bind("connection", connectionId)
        .bind("t", tenantKey.value)
        .bind("by", default?.setBy)
        .bind("now", now)
        .mapTo(Boolean::class.java)
        .findOne()
        .orElse(false)
}

private class MirrorDefaultRow(val share: Boolean, val shareByDefault: Boolean, val consentVersion: String, val setBy: UUID?)

/** The default as it stands, with the name of who set it; [CatalogMirrorDefault.NONE] when never set. */
internal fun readMirrorDefault(handle: Handle, connectionId: UUID): CatalogMirrorDefault = handle.createQuery(
    """
    SELECT d.share, COALESCE(${shareByDefault("d")}, FALSE) AS share_by_default, d.statement_version, d.set_at, u.display_name AS set_by, d.from_configuration
    FROM support_sharing_default d LEFT JOIN users u ON u.id = d.set_by
    WHERE ${mirrorSharing("d")}
    """,
)
    .bind("connection", connectionId)
    .map { rs, _ ->
        val choice = when {
            !rs.getBoolean("share") -> CatalogMirrorDefaultChoice.OFF
            rs.getBoolean("share_by_default") -> CatalogMirrorDefaultChoice.ALL
            else -> CatalogMirrorDefaultChoice.MARKED
        }
        CatalogMirrorDefault(choice, rs.getString("statement_version"), rs.getObject("set_at", OffsetDateTime::class.java), rs.getString("set_by"), rs.getBoolean("from_configuration"))
    }
    .findOne()
    .orElse(CatalogMirrorDefault.NONE)
