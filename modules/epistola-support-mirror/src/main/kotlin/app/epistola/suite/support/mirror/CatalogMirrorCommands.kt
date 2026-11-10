// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror

import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.cluster.timers.ScheduleClusterTimer
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.support.privacy.SupportPrivacy
import app.epistola.suite.support.sharing.SharingRestrictions
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/** A tenant's consent record, as it stands after an opt-in or opt-out. */
data class CatalogMirrorConsentResult(
    val tenantKey: TenantKey,
    val shareByDefault: Boolean,
    val consentVersion: String?,
    val optedInAt: OffsetDateTime?,
    val optedOutAt: OffsetDateTime?,
) {
    val sharing: Boolean get() = optedOutAt == null
}

/** The consent asked for names a statement of what is shared that is not the current one. */
class CatalogMirrorConsentOutdatedException(version: String) : RuntimeException("Consent version $version is not the current statement (${CatalogMirror.CONSENT_VERSION}); reload the page and read it again")

/** Tenants are confidential (ADR 0052 §1): a release names its client whatever key it is filed under, so nothing is mirrored. */
class CatalogMirrorConfidentialException : IllegalStateException("The catalog mirror is unavailable while tenants are confidential to that support connection")

/** The tenant has never opted in to the catalog mirror, so there is nothing to sync. */
class CatalogMirrorNotOptedInException(tenantKey: TenantKey) : RuntimeException("Tenant ${tenantKey.value} does not share catalogs with that support connection")

/**
 * The tenant starts sharing catalog releases with support connection [connectionId], with an explicit
 * choice: [shareByDefault] true shares every catalog that has no policy of its own, new ones
 * included; false shares only the catalogs marked [CatalogMirrorPolicy.SHARE]. Records who agreed,
 * when, and to which [consentVersion]. Opting in again (also to change the choice) replaces the
 * record. It is the tenant's own choice: it no longer follows the installation default
 * ([CatalogMirrorDefault]). Sends tenant data out, so it needs [Permission.INTEGRATIONS_MANAGE].
 */
data class OptInCatalogMirror(
    override val tenantKey: TenantKey,
    val shareByDefault: Boolean,
    val consentVersion: String,
    /** The connection the tenant shares its catalogs with. */
    val connectionId: UUID = DEFAULT_MIRROR_CONNECTION,
) : Command<CatalogMirrorConsentResult>,
    RequiresPermission {
    override val permission = Permission.INTEGRATIONS_MANAGE
}

@Component
class OptInCatalogMirrorHandler(
    private val jdbi: Jdbi,
    private val privacy: SupportPrivacy,
) : CommandHandler<OptInCatalogMirror, CatalogMirrorConsentResult> {
    override fun handle(command: OptInCatalogMirror): CatalogMirrorConsentResult {
        if (privacy.forConnection(command.connectionId).confidential) throw CatalogMirrorConfidentialException()
        if (command.consentVersion != CatalogMirror.CONSENT_VERSION) throw CatalogMirrorConsentOutdatedException(command.consentVersion)
        return jdbi.withHandle<CatalogMirrorConsentResult, Exception> { handle ->
            SharingRestrictions.requireAllowed(handle, CatalogMirror.SECTION, command.connectionId)
            handle.createQuery(
                """
                INSERT INTO support_sharing_choice (connection_id, section, tenant_key, options, statement_version, given_at, given_by, follows_default)
                VALUES (:connection, '${CatalogMirror.SECTION}', :t, jsonb_build_object('shareByDefault', :shareByDefault), :version, :now,
                        (SELECT id FROM users WHERE id = :by), FALSE)
                ON CONFLICT (connection_id, section, tenant_key) DO UPDATE SET
                    follows_default = FALSE,
                    options = EXCLUDED.options,
                    statement_version = EXCLUDED.statement_version,
                    given_at = EXCLUDED.given_at,
                    given_by = EXCLUDED.given_by,
                    withdrawn_at = NULL,
                    withdrawn_by = NULL,
                    withdrawal_confirmed_at = NULL
                RETURNING tenant_key, (options->>'shareByDefault')::boolean AS share_by_default, statement_version AS consent_version, given_at AS opted_in_at, withdrawn_at AS opted_out_at
                """,
            )
                .bind("t", command.tenantKey.value)
                .bind("connection", command.connectionId)
                .bind("shareByDefault", command.shareByDefault)
                .bind("version", command.consentVersion)
                .bind("now", EpistolaClock.offsetDateTime())
                .bind("by", SecurityContext.current().userId.value)
                .map { rs, _ -> consentResult(rs) }
                .one()
        }
    }
}

/**
 * The tenant stops sharing, as its own choice: it no longer follows the installation default
 * ([CatalogMirrorDefault]), also when it never shared (a record without an opt-in keeps that choice,
 * with nothing to withdraw). The record of an opt-in stays; the next reconcile withdraws the tenant
 * from the mirror, which deletes what it held of it, and records when the service confirmed that.
 * A withdrawal is scheduled at once rather than at the next round.
 */
data class OptOutCatalogMirror(
    override val tenantKey: TenantKey,
    val connectionId: UUID = DEFAULT_MIRROR_CONNECTION,
) : Command<CatalogMirrorConsentResult?>,
    RequiresPermission {
    override val permission = Permission.INTEGRATIONS_MANAGE
}

@Component
class OptOutCatalogMirrorHandler(
    private val jdbi: Jdbi,
) : CommandHandler<OptOutCatalogMirror, CatalogMirrorConsentResult?> {
    override fun handle(command: OptOutCatalogMirror): CatalogMirrorConsentResult? {
        val result = jdbi.withHandle<CatalogMirrorConsentResult?, Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO support_sharing_choice (connection_id, section, tenant_key, options, withdrawn_at, withdrawn_by, withdrawal_confirmed_at, follows_default)
                VALUES (:connection, '${CatalogMirror.SECTION}', :t, jsonb_build_object('shareByDefault', FALSE), :now,
                        (SELECT id FROM users WHERE id = :by), :now, FALSE)
                ON CONFLICT (connection_id, section, tenant_key) DO UPDATE SET
                    follows_default = FALSE,
                    withdrawal_confirmed_at = CASE WHEN support_sharing_choice.withdrawn_at IS NULL THEN NULL ELSE support_sharing_choice.withdrawal_confirmed_at END,
                    withdrawn_by = CASE WHEN support_sharing_choice.withdrawn_at IS NULL THEN EXCLUDED.withdrawn_by ELSE support_sharing_choice.withdrawn_by END,
                    withdrawn_at = COALESCE(support_sharing_choice.withdrawn_at, EXCLUDED.withdrawn_at)
                """,
            )
                .bind("t", command.tenantKey.value)
                .bind("connection", command.connectionId)
                .bind("now", EpistolaClock.offsetDateTime())
                .bind("by", SecurityContext.current().userId.value)
                .execute()
            handle.createQuery("SELECT tenant_key, (options->>'shareByDefault')::boolean AS share_by_default, statement_version AS consent_version, given_at AS opted_in_at, withdrawn_at AS opted_out_at FROM support_sharing_choice WHERE ${mirrorSharing()} AND tenant_key = :t")
                .bind("t", command.tenantKey.value)
                .bind("connection", command.connectionId)
                .map { rs, _ -> consentResult(rs) }
                .findOne()
                .orElse(null)
        }
        if (result != null) scheduleSync(jdbi, command.connectionId, command.tenantKey)
        return result
    }
}

/** A catalog's mirror policy as it now stands. */
data class CatalogMirrorPolicyResult(val catalog: CatalogId, val policy: CatalogMirrorPolicy)

/**
 * Sets one catalog's mirror policy: [CatalogMirrorPolicy.INHERIT] removes its own. Any catalog the
 * tenant has can be set, received, installed and those Epistola supplies included. Tightening is easy and loosening is not: withholding needs
 * [Permission.CATALOG_MANAGE], sharing or inheriting needs [Permission.INTEGRATIONS_MANAGE].
 */
data class SetCatalogMirrorPolicy(
    val catalog: CatalogId,
    val policy: CatalogMirrorPolicy,
    /** The connection whose sharing the policy is part of: each has its own. */
    val connectionId: UUID = DEFAULT_MIRROR_CONNECTION,
) : Command<CatalogMirrorPolicyResult>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    override val permission: Permission
        get() = if (policy == CatalogMirrorPolicy.NEVER) Permission.CATALOG_MANAGE else Permission.INTEGRATIONS_MANAGE
}

@Component
class SetCatalogMirrorPolicyHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SetCatalogMirrorPolicy, CatalogMirrorPolicyResult> {
    override fun handle(command: SetCatalogMirrorPolicy): CatalogMirrorPolicyResult = jdbi.withHandle<CatalogMirrorPolicyResult, Exception> { handle ->
        val target = handle.createQuery(
            """
            SELECT c.catalog_id
            FROM catalogs c WHERE c.tenant_key = :t AND c.namespace = :ns AND c.catalog_key = :key
            """,
        )
            .bind("t", command.tenantKey.value)
            .bind("ns", command.catalog.namespace)
            .bind("key", command.catalog.key.value)
            .map { rs, _ -> rs.getObject("catalog_id", java.util.UUID::class.java) }
            .findOne()
            .orElseThrow { CatalogNotFoundException(command.catalog.key) }
        if (command.policy == CatalogMirrorPolicy.INHERIT) {
            handle.createUpdate("DELETE FROM support_mirror_catalog_policies WHERE connection_id = :connection AND tenant_key = :t AND catalog_id = :c")
                .bind("t", command.tenantKey.value)
                .bind("connection", command.connectionId)
                .bind("c", target)
                .execute()
        } else {
            handle.createUpdate(
                """
                INSERT INTO support_mirror_catalog_policies (connection_id, tenant_key, catalog_id, policy, set_at, set_by)
                VALUES (:connection, :t, :c, :policy, :now, :by)
                ON CONFLICT (connection_id, tenant_key, catalog_id) DO UPDATE SET policy = EXCLUDED.policy, set_at = EXCLUDED.set_at, set_by = EXCLUDED.set_by
                """,
            )
                .bind("t", command.tenantKey.value)
                .bind("connection", command.connectionId)
                .bind("c", target)
                .bind("policy", command.policy.name)
                .bind("now", EpistolaClock.offsetDateTime())
                .bind("by", SecurityContext.current().userId.value)
                .execute()
        }
        CatalogMirrorPolicyResult(command.catalog, command.policy)
    }
}

/** A sync was asked for; the reconcile runs it in the background, on whichever node takes it. */
data class CatalogMirrorSyncRequested(val tenantKey: TenantKey, val connectionId: UUID, val requestedAt: OffsetDateTime)

/**
 * "Sync now": reconciles the tenant with the mirror in the background, instead of at the next
 * round, whatever changed or did not. For a tenant that opted out it withdraws the tenant.
 */
data class RequestCatalogMirrorSync(
    override val tenantKey: TenantKey,
    val connectionId: UUID = DEFAULT_MIRROR_CONNECTION,
) : Command<CatalogMirrorSyncRequested>,
    RequiresPermission {
    override val permission = Permission.INTEGRATIONS_MANAGE
}

@Component
class RequestCatalogMirrorSyncHandler(
    private val jdbi: Jdbi,
) : CommandHandler<RequestCatalogMirrorSync, CatalogMirrorSyncRequested> {
    override fun handle(command: RequestCatalogMirrorSync): CatalogMirrorSyncRequested {
        val consented = jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery("SELECT EXISTS (SELECT 1 FROM support_sharing_choice WHERE ${mirrorSharing()} AND tenant_key = :t)")
                .bind("t", command.tenantKey.value)
                .bind("connection", command.connectionId)
                .mapTo(Boolean::class.java)
                .one()
        }
        if (!consented) throw CatalogMirrorNotOptedInException(command.tenantKey)
        return CatalogMirrorSyncRequested(command.tenantKey, command.connectionId, scheduleSync(jdbi, command.connectionId, command.tenantKey))
    }
}

/** Marks [tenantKey] as waiting for a sync with [connectionId] and schedules the cluster timer that runs it now. */
internal fun scheduleSync(jdbi: Jdbi, connectionId: UUID, tenantKey: TenantKey): OffsetDateTime {
    val now = EpistolaClock.offsetDateTime()
    jdbi.useHandle<Exception> { handle ->
        handle.createUpdate(
            """
            INSERT INTO support_mirror_state (connection_id, tenant_key, sync_requested_at) VALUES (:connection, :t, :now)
            ON CONFLICT (connection_id, tenant_key) DO UPDATE SET sync_requested_at = EXCLUDED.sync_requested_at
            """,
        )
            .bind("connection", connectionId)
            .bind("t", tenantKey.value)
            .bind("now", now)
            .execute()
    }
    ScheduleClusterTimer(
        timerKey = CatalogMirrorSyncTimerHandler.timerKey(connectionId, tenantKey),
        routingKey = "system:${CatalogMirrorSyncTimerHandler.TIMER_TYPE}",
        timerType = CatalogMirrorSyncTimerHandler.TIMER_TYPE,
        dueAt = now,
        payload = mapOf(CatalogMirrorSyncTimerHandler.CONNECTION to connectionId.toString()),
        tenantKey = tenantKey,
    ).execute()
    return now
}

private fun consentResult(rs: java.sql.ResultSet) = CatalogMirrorConsentResult(
    tenantKey = TenantKey.of(rs.getString("tenant_key")),
    shareByDefault = rs.getBoolean("share_by_default"),
    consentVersion = rs.getString("consent_version"),
    optedInAt = rs.getObject("opted_in_at", OffsetDateTime::class.java),
    optedOutAt = rs.getObject("opted_out_at", OffsetDateTime::class.java),
)
