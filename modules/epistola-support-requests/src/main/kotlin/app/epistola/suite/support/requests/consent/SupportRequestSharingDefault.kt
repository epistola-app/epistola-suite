// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.consent

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
import app.epistola.suite.support.sharing.SharingRestrictions
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The installation's default for feedback sharing (`support_sharing_default`, section `requests`): it applies directly to
 * every tenant without a choice of its own, written into that tenant's consent as given or withdrawn
 * by the installation administrator who set it ([SupportRequestSyncConsent.followsDefault]). A tenant's own
 * choice wins until it follows the default again ([FollowSupportRequestSharingDefault]).
 *
 * @property share the default shares feedback; false also when none was ever set.
 * @property setAt when it was last set; null when never.
 * @property setBy display name of who set it; null when never, when it came from the configuration, or that user is gone.
 * @property fromConfiguration it came from the deployment configuration
 *   (`epistola.support.tenant-defaults.requests`), not from an installation administrator.
 */
data class SupportRequestSharingDefault(
    val share: Boolean,
    val setAt: OffsetDateTime?,
    val setBy: String?,
    val fromConfiguration: Boolean = false,
) {
    /** A preset exists: an installation administrator saved one, or the configuration provided it. */
    val isSet: Boolean get() = setAt != null

    companion object {
        val NONE = SupportRequestSharingDefault(share = false, setAt = null, setBy = null)
    }
}

/** The installation's default for feedback sharing, for the platform page and every tenant's settings. */
class GetSupportRequestSharingDefault :
    Query<SupportRequestSharingDefault>,
    RequiresAuthentication

@Component
class GetSupportRequestSharingDefaultHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetSupportRequestSharingDefault, SupportRequestSharingDefault> {
    override fun handle(query: GetSupportRequestSharingDefault): SupportRequestSharingDefault = jdbi.withHandleUnchecked { readDefault(it) }
}

/** What setting the default did: the default as it now stands, and how many tenants it changed. */
data class SupportRequestSharingDefaultResult(val default: SupportRequestSharingDefault, val tenantsChanged: Int)

/**
 * Sets the installation's default for feedback sharing and applies it at once to every tenant that
 * follows it: sharing gives their consent, not sharing withdraws it and stops what waits to be sent,
 * exactly as their own withdrawal would. Tenants with a choice of their own are left alone.
 */
data class SetSupportRequestSharingDefault(
    val share: Boolean,
) : Command<SupportRequestSharingDefaultResult>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

@Component
class SetSupportRequestSharingDefaultHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SetSupportRequestSharingDefault, SupportRequestSharingDefaultResult> {
    override fun handle(command: SetSupportRequestSharingDefault): SupportRequestSharingDefaultResult = jdbi.withHandleUnchecked { handle ->
        val changed = setSupportRequestDefault(handle, command.share, SecurityContext.current().userId.value, fromConfiguration = false)
        SupportRequestSharingDefaultResult(readDefault(handle), changed)
    }
}

/**
 * Removes the installation preset: every tenant that followed it stops sharing, exactly as by its
 * own withdrawal, and has to set itself up (its Support page shows the setup wizard again).
 */
class ClearSupportRequestSharingDefault :
    Command<SupportRequestSharingDefaultResult>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

@Component
class ClearSupportRequestSharingDefaultHandler(
    private val jdbi: Jdbi,
) : CommandHandler<ClearSupportRequestSharingDefault, SupportRequestSharingDefaultResult> {
    override fun handle(command: ClearSupportRequestSharingDefault): SupportRequestSharingDefaultResult = jdbi.withHandleUnchecked { handle ->
        val stopped = withdrawForFollowers(handle, EpistolaClock.offsetDateTime(), SecurityContext.current().userId.value)
        handle.createUpdate("DELETE FROM support_sharing_default WHERE connection_id = $REQUESTS_CONNECTION_SQL AND section = '$REQUESTS_SECTION'").execute()
        SupportRequestSharingDefaultResult(SupportRequestSharingDefault.NONE, stopped)
    }
}

/**
 * Stores the default as set by [userId] (null: by the configuration) and applies it to every tenant
 * that follows it: sharing gives their consent, not sharing withdraws it and stops what waits.
 *
 * @return the tenants it changed.
 */
internal fun setSupportRequestDefault(handle: Handle, share: Boolean, userId: UUID?, fromConfiguration: Boolean): Int {
    if (share) SharingRestrictions.requireAllowed(handle, REQUESTS_SECTION, REQUESTS_CONNECTION)
    val now = EpistolaClock.offsetDateTime()
    handle.createUpdate(
        """
        INSERT INTO support_sharing_default (connection_id, section, share, set_at, set_by, from_configuration)
        VALUES ($REQUESTS_CONNECTION_SQL, '$REQUESTS_SECTION', :share, :now, (SELECT id FROM users WHERE id = :userId), :fromConfiguration)
        ON CONFLICT (connection_id, section) DO UPDATE SET
            share = EXCLUDED.share, set_at = EXCLUDED.set_at, set_by = EXCLUDED.set_by, from_configuration = EXCLUDED.from_configuration
        """,
    )
        .bind("share", share)
        .bind("now", now)
        .bind("userId", userId)
        .bind("fromConfiguration", fromConfiguration)
        .execute()
    return if (share) shareForFollowers(handle, now, userId) else withdrawForFollowers(handle, now, userId)
}

/** Gives consent for every tenant without a row, and again for followers whose consent is withdrawn. */
private fun shareForFollowers(handle: Handle, now: OffsetDateTime, userId: UUID?): Int {
    val added = handle.createUpdate(
        """
        INSERT INTO support_sharing_choice (connection_id, section, tenant_key, given_at, given_by, follows_default)
        SELECT $REQUESTS_CONNECTION_SQL, '$REQUESTS_SECTION', t.id, :now, (SELECT id FROM users WHERE id = :userId), TRUE
        FROM tenants t
        WHERE NOT EXISTS (SELECT 1 FROM support_sharing_choice c WHERE c.connection_id = $REQUESTS_CONNECTION_SQL AND c.section = '$REQUESTS_SECTION' AND c.tenant_key = t.id)
        """,
    )
        .bind("now", now)
        .bind("userId", userId)
        .execute()
    val regiven = handle.createUpdate(
        """
        UPDATE support_sharing_choice
        SET grant_id = gen_random_uuid(), given_at = :now, given_by = (SELECT id FROM users WHERE id = :userId),
            withdrawn_at = NULL, withdrawn_by = NULL
        WHERE connection_id = $REQUESTS_CONNECTION_SQL AND section = '$REQUESTS_SECTION' AND follows_default AND withdrawn_at IS NOT NULL
        """,
    )
        .bind("now", now)
        .bind("userId", userId)
        .execute()
    return added + regiven
}

/** Withdraws the consent of followers in force, locking their rows first, and stops what waits. */
private fun withdrawForFollowers(handle: Handle, now: OffsetDateTime, userId: UUID?): Int {
    val withdrawn = handle.createQuery(
        """
        UPDATE support_sharing_choice
        SET withdrawn_at = :now, withdrawn_by = (SELECT id FROM users WHERE id = :userId)
        WHERE connection_id = $REQUESTS_CONNECTION_SQL AND section = '$REQUESTS_SECTION' AND follows_default AND withdrawn_at IS NULL
        RETURNING tenant_key
        """,
    )
        .bind("now", now)
        .bind("userId", userId)
        .mapTo(String::class.java)
        .list()
        .map(TenantKey::of)
    SupportRequestConsentWrites.stopWaiting(handle, withdrawn)
    return withdrawn.size
}

/**
 * Applies the preset of the deployment configuration (`epistola.support.tenant-defaults.requests`)
 * at startup, only while no default is stored: once an installation administrator saved one, that
 * wins. Idempotent across nodes: only the node that stores it applies it.
 */
@Component
class ApplyConfiguredSupportRequestSharingDefault(
    private val jdbi: Jdbi,
    private val properties: SupportProperties,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun apply() {
        val share = properties.tenantDefaults.requestsShare ?: return
        if (!properties.enabled) return
        jdbi.inTransaction<Unit, Exception> { handle ->
            // Not allowed wins over a configured default: nothing is stored or applied.
            if (SharingRestrictions.isNotAllowed(handle, REQUESTS_SECTION, REQUESTS_CONNECTION)) return@inTransaction
            // Applied once per configured value: a preset an administrator cleared, or that a disconnect
            // forgot, does not come back at the next start.
            val applied = handle.createQuery("SELECT value ->> 'requests' FROM app_metadata WHERE key = :key")
                .bind("key", APPLIED_KEY).mapTo(String::class.java).findOne().orElse(null)
            if (applied == share.toString()) return@inTransaction
            handle.createUpdate(
                "INSERT INTO app_metadata (key, value, updated_at) VALUES (:key, jsonb_build_object('requests', CAST(:share AS TEXT)), NOW()) " +
                    "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()",
            ).bind("key", APPLIED_KEY).bind("share", share.toString()).execute()
            val stored = handle.createUpdate(
                "INSERT INTO support_sharing_default (connection_id, section, share, set_at, from_configuration) VALUES ($REQUESTS_CONNECTION_SQL, '$REQUESTS_SECTION', :share, :now, TRUE) ON CONFLICT (connection_id, section) DO NOTHING",
            )
                .bind("share", share)
                .bind("now", EpistolaClock.offsetDateTime())
                .execute()
            if (stored == 1) setSupportRequestDefault(handle, share, userId = null, fromConfiguration = true)
        }
    }

    companion object {
        /** The configured preset last applied (`app_metadata`), so it is applied once per value. */
        const val APPLIED_KEY = "support.requests.configured-default-applied"
    }
}

/**
 * The tenant drops its own choice and follows the installation default again, which applies at once:
 * consent is given when the default shares, and withdrawn (stopping what waits) when it does not.
 */
data class FollowSupportRequestSharingDefault(
    override val tenantKey: TenantKey,
) : Command<SupportRequestSyncConsent>,
    RequiresPermission {
    override val permission get() = Permission.INTEGRATIONS_MANAGE
}

@Component
class FollowSupportRequestSharingDefaultHandler(
    private val jdbi: Jdbi,
) : CommandHandler<FollowSupportRequestSharingDefault, SupportRequestSyncConsent> {
    override fun handle(command: FollowSupportRequestSharingDefault): SupportRequestSyncConsent = jdbi.withHandleUnchecked { handle ->
        applyDefault(handle, command.tenantKey)
        handle.createQuery(SELECT_SUPPORT_REQUEST_SYNC_CONSENT)
            .bind("tenantKey", command.tenantKey)
            .mapTo(SupportRequestSyncConsent::class.java)
            .findOne()
            .orElse(SupportRequestSyncConsent.none(command.tenantKey))
    }
}

/**
 * A new tenant follows the installation default from the start, in the transaction that creates it,
 * so feedback written in its first minutes is already shared when the default says so.
 */
@Component
class ApplySupportRequestSharingDefaultToNewTenant(
    private val jdbi: Jdbi,
) : EventHandler<CreateTenant> {
    override val phase = EventPhase.IMMEDIATE

    override fun on(event: CreateTenant, result: Any?) {
        jdbi.withHandleUnchecked { handle -> applyDefault(handle, event.id) }
    }
}

/**
 * Makes [tenantKey] follow the default as it stands now: given by, or withdrawn by, the administrator
 * who set it, so the record says whose decision it was.
 */
private fun applyDefault(handle: Handle, tenantKey: TenantKey) {
    val row = handle.createQuery("SELECT share, set_by FROM support_sharing_default WHERE connection_id = $REQUESTS_CONNECTION_SQL AND section = '$REQUESTS_SECTION'")
        .map { rs, _ -> rs.getBoolean("share") to rs.getObject("set_by", UUID::class.java) }
        .findOne()
        .orElse(false to null)
    val (share, setBy) = row
    val now = EpistolaClock.offsetDateTime()
    if (share) {
        SupportRequestConsentWrites.grant(handle, tenantKey, setBy, now, followsDefault = true)
    } else {
        val withdrawn = handle.createUpdate(
            """
            UPDATE support_sharing_choice
            SET follows_default = TRUE,
                withdrawn_by = CASE WHEN withdrawn_at IS NULL THEN CAST(:setBy AS UUID) ELSE withdrawn_by END,
                withdrawn_at = COALESCE(withdrawn_at, :now)
            WHERE connection_id = $REQUESTS_CONNECTION_SQL AND section = '$REQUESTS_SECTION' AND tenant_key = :tenantKey
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("setBy", setBy)
            .bind("now", now)
            .execute()
        if (withdrawn > 0) SupportRequestConsentWrites.stopWaiting(handle, listOf(tenantKey))
    }
}

private fun readDefault(handle: Handle): SupportRequestSharingDefault = handle.createQuery(
    """
    SELECT d.share, d.set_at, u.display_name AS set_by, d.from_configuration
    FROM support_sharing_default d LEFT JOIN users u ON u.id = d.set_by
    WHERE d.connection_id = $REQUESTS_CONNECTION_SQL AND d.section = '$REQUESTS_SECTION'
    """,
)
    .map { rs, _ ->
        SupportRequestSharingDefault(rs.getBoolean("share"), rs.getObject("set_at", OffsetDateTime::class.java), rs.getString("set_by"), rs.getBoolean("from_configuration"))
    }
    .findOne()
    .orElse(SupportRequestSharingDefault.NONE)

/** The key of feedback as a shared service: its sharing section and its restriction. */
const val REQUESTS_SECTION = "requests"
