// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.usage

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresAuthentication
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.support.connection.SupportConnection
import app.epistola.suite.support.connection.SupportConnections
import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.sharing.InstallationSharingView
import app.epistola.suite.support.sharing.SharingChoice
import app.epistola.suite.support.sharing.SharingRestrictions
import app.epistola.suite.support.sharing.TenantSharingSection
import app.epistola.suite.support.sharing.TenantSharingView
import app.epistola.suite.support.sharing.field
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/** The key of usage as a shared service: its sharing section and its restriction. */
const val USAGE_SECTION = "usage"

/** A tenant's own choice about sharing its usage; null fields when it follows the installation default. */
data class UsageChoice(val tenantKey: TenantKey, val share: Boolean?, val decidedAt: OffsetDateTime?, val decidedBy: String?)

/** The installation default; [isSet] false when none was saved (usage is then not shared). */
data class UsageDefault(val share: Boolean, val setAt: OffsetDateTime?, val setBy: String?) {
    val isSet: Boolean get() = setAt != null
}

/** A tenant's own choice, or that it follows the default. */
data class GetUsageChoice(override val tenantKey: TenantKey, val connectionId: UUID = SupportConnections.DISCOVERED_ID) :
    Query<UsageChoice>,
    RequiresPermission {
    override val permission get() = Permission.INTEGRATIONS_MANAGE
}

@Component
class GetUsageChoiceHandler(private val jdbi: Jdbi) : QueryHandler<GetUsageChoice, UsageChoice> {
    override fun handle(query: GetUsageChoice): UsageChoice = jdbi.withHandle<UsageChoice, Exception> { handle ->
        handle.createQuery(
            """
            SELECT c.withdrawn_at IS NULL AS share, COALESCE(c.withdrawn_at, c.given_at) AS decided_at, u.display_name
            FROM support_sharing_choice c LEFT JOIN users u ON u.id = COALESCE(c.withdrawn_by, c.given_by)
            WHERE c.connection_id = :connection AND c.section = '$USAGE_SECTION' AND c.tenant_key = :t
            """,
        )
            .bind("connection", query.connectionId)
            .bind("t", query.tenantKey)
            .map { rs, _ -> UsageChoice(query.tenantKey, rs.getBoolean("share"), rs.getObject("decided_at", OffsetDateTime::class.java), rs.getString("display_name")) }
            .findOne()
            .orElse(UsageChoice(query.tenantKey, null, null, null))
    }
}

data class GetUsageDefault(val connectionId: UUID = SupportConnections.DISCOVERED_ID) :
    Query<UsageDefault>,
    RequiresAuthentication

@Component
class GetUsageDefaultHandler(private val jdbi: Jdbi) : QueryHandler<GetUsageDefault, UsageDefault> {
    override fun handle(query: GetUsageDefault): UsageDefault = jdbi.withHandle<UsageDefault, Exception> { handle ->
        handle.createQuery("SELECT d.share, d.set_at, u.display_name FROM support_sharing_default d LEFT JOIN users u ON u.id = d.set_by WHERE d.connection_id = :connection AND d.section = '$USAGE_SECTION'")
            .bind("connection", query.connectionId)
            .map { rs, _ -> UsageDefault(rs.getBoolean("share"), rs.getObject("set_at", OffsetDateTime::class.java), rs.getString("display_name")) }
            .findOne()
            .orElse(UsageDefault(false, null, null))
    }
}

/** The tenant's own choice. Sharing is refused while usage is not allowed. */
data class SetUsageChoice(override val tenantKey: TenantKey, val share: Boolean, val connectionId: UUID = SupportConnections.DISCOVERED_ID) :
    Command<UsageChoice>,
    RequiresPermission {
    override val permission get() = Permission.INTEGRATIONS_MANAGE
}

@Component
class SetUsageChoiceHandler(private val jdbi: Jdbi) : CommandHandler<SetUsageChoice, UsageChoice> {
    override fun handle(command: SetUsageChoice): UsageChoice {
        jdbi.useHandle<Exception> { handle ->
            if (command.share) SharingRestrictions.requireAllowed(handle, USAGE_SECTION, command.connectionId)
            handle.createUpdate(
                """
                INSERT INTO support_sharing_choice (connection_id, section, tenant_key, given_at, given_by, withdrawn_at, withdrawn_by)
                SELECT :connection, '$USAGE_SECTION', :t, CASE WHEN :share THEN c.at END, CASE WHEN :share THEN c.by END, CASE WHEN :share THEN NULL ELSE c.at END, CASE WHEN :share THEN NULL ELSE c.by END
                FROM (SELECT CAST(:now AS TIMESTAMPTZ) AS at, (SELECT id FROM users WHERE id = :by) AS by) c
                ON CONFLICT (connection_id, section, tenant_key) DO UPDATE SET
                    given_at = CASE WHEN :share THEN EXCLUDED.given_at ELSE support_sharing_choice.given_at END,
                    given_by = CASE WHEN :share THEN EXCLUDED.given_by ELSE support_sharing_choice.given_by END,
                    withdrawn_at = EXCLUDED.withdrawn_at, withdrawn_by = EXCLUDED.withdrawn_by
                """,
            )
                .bind("connection", command.connectionId)
                .bind("t", command.tenantKey)
                .bind("share", command.share)
                .bind("now", EpistolaClock.offsetDateTime())
                .bind("by", SecurityContext.current().userId.value)
                .execute()
        }
        return GetUsageChoice(command.tenantKey, command.connectionId).query()
    }
}

/** The tenant drops its own choice and follows the installation default. */
data class FollowUsageDefault(override val tenantKey: TenantKey, val connectionId: UUID = SupportConnections.DISCOVERED_ID) :
    Command<UsageChoice>,
    RequiresPermission {
    override val permission get() = Permission.INTEGRATIONS_MANAGE
}

@Component
class FollowUsageDefaultHandler(private val jdbi: Jdbi) : CommandHandler<FollowUsageDefault, UsageChoice> {
    override fun handle(command: FollowUsageDefault): UsageChoice {
        jdbi.useHandle<Exception> {
            it.createUpdate("DELETE FROM support_sharing_choice WHERE connection_id = :connection AND section = '$USAGE_SECTION' AND tenant_key = :t")
                .bind("connection", command.connectionId).bind("t", command.tenantKey).execute()
        }
        return GetUsageChoice(command.tenantKey, command.connectionId).query()
    }
}

/** The installation default for tenants without a choice of their own. Sharing is refused while usage is not allowed. */
data class SetUsageDefault(val share: Boolean, val connectionId: UUID = SupportConnections.DISCOVERED_ID) :
    Command<UsageDefault>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

@Component
class SetUsageDefaultHandler(private val jdbi: Jdbi) : CommandHandler<SetUsageDefault, UsageDefault> {
    override fun handle(command: SetUsageDefault): UsageDefault {
        jdbi.useHandle<Exception> { handle ->
            if (command.share) SharingRestrictions.requireAllowed(handle, USAGE_SECTION, command.connectionId)
            handle.createUpdate(
                """
                INSERT INTO support_sharing_default (connection_id, section, share, set_at, set_by)
                VALUES (:connection, '$USAGE_SECTION', :share, :now, (SELECT id FROM users WHERE id = :by))
                ON CONFLICT (connection_id, section) DO UPDATE SET share = EXCLUDED.share, set_at = EXCLUDED.set_at, set_by = EXCLUDED.set_by
                """,
            )
                .bind("connection", command.connectionId)
                .bind("share", command.share)
                .bind("now", EpistolaClock.offsetDateTime())
                .bind("by", SecurityContext.current().userId.value)
                .execute()
        }
        return GetUsageDefault(command.connectionId).query()
    }
}

/**
 * Forgets every tenant's usage choice and the default, as on disconnecting or when usage becomes not
 * allowed. What was counted stays: it is this installation's own record.
 */
data class ForgetUsageSharing(val connectionId: UUID = SupportConnections.DISCOVERED_ID) :
    Command<ForgottenUsageSharing>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

@Component
class ForgetUsageSharingHandler(private val jdbi: Jdbi) : CommandHandler<ForgetUsageSharing, ForgottenUsageSharing> {
    override fun handle(command: ForgetUsageSharing): ForgottenUsageSharing = jdbi.withHandle<ForgottenUsageSharing, Exception> { handle ->
        val tenants = handle.createUpdate("DELETE FROM support_sharing_choice WHERE connection_id = :connection AND section = '$USAGE_SECTION'").bind("connection", command.connectionId).execute()
        handle.createUpdate("DELETE FROM support_sharing_default WHERE connection_id = :connection AND section = '$USAGE_SECTION'").bind("connection", command.connectionId).execute()
        ForgottenUsageSharing(tenants)
    }
}

/** What [ForgetUsageSharing] did: the tenants whose own choice it forgot. */
data class ForgottenUsageSharing(val tenants: Int)

/**
 * Usage as a step of the tenant's setup and as part of the installation's settings (ADR 0052 §5–6).
 * Usage is no tenant content, so an installation may make it Required; it offers no "ask each tenant".
 */
@Component
class UsageSharingSection(private val connection: SupportConnection) : TenantSharingSection {
    override val key = USAGE_SECTION
    override val order = 30
    override val title = "Usage"
    override val summary = "For each hour, how many documents were generated and previews rendered: numbers only, no content."
    override val statementFragment = "support/usage :: statement"
    override val choices = listOf(
        SharingChoice(SHARE, "Share usage counts with Epistola", shares = true),
        SharingChoice(NO, "Keep usage counts in this installation", shares = false),
    )
    override val offersNoPreset = false
    override val offersRequired = true

    /** Asked only once the installation selected usage under Services. */
    override fun offered(connectionId: UUID): Boolean = connection.isSelected(SupportScope.USAGE)

    override fun tenantView(tenantKey: TenantKey, connectionId: UUID): TenantSharingView {
        val choice = GetUsageChoice(tenantKey, connectionId).query()
        val default = GetUsageDefault(connectionId).query()
        val share = choice.share ?: default.share
        return TenantSharingView(
            section = this,
            value = if (share) SHARE else NO,
            followsPreset = choice.share == null,
            decidedBy = choice.decidedBy ?: default.setBy,
            decidedAt = choice.decidedAt ?: default.setAt,
        )
    }

    override fun installationView(connectionId: UUID): InstallationSharingView {
        val default = GetUsageDefault(connectionId).query()
        return InstallationSharingView(this, default.isSet, if (default.share) SHARE else NO, fromConfiguration = false, default.setBy, default.setAt)
    }

    override fun applyOwn(tenantKey: TenantKey, value: String, connectionId: UUID) {
        SetUsageChoice(tenantKey, value == SHARE, connectionId).execute()
    }

    override fun followPreset(tenantKey: TenantKey, connectionId: UUID) {
        FollowUsageDefault(tenantKey, connectionId).execute()
    }

    override fun saveInstallation(form: Map<String, List<String>>, connectionId: UUID): String? {
        val share = when (form.field(installationView(connectionId).field)) {
            SHARE -> true
            NO -> false
            else -> return null
        }
        val current = GetUsageDefault(connectionId).query()
        if (current.isSet && current.share == share) return null
        SetUsageDefault(share, connectionId).execute()
        return if (share) "Usage: shared by default." else "Usage: not shared by default."
    }

    override fun forgetAll(connectionId: UUID) {
        ForgetUsageSharing(connectionId).execute()
    }

    companion object {
        const val SHARE = "SHARE"
        const val NO = "NO"
    }
}
