// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.sharing

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresAuthentication
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.support.connection.SupportConnections
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * What an installation administrator decided for a shared service beyond a default (ADR 0052 §5).
 * Without one, tenants choose and follow the service's own default.
 */
enum class SharingRestriction {
    /** No tenant shares it, and none can opt in. */
    NOT_ALLOWED,

    /** Every tenant shares it, without a choice. Offered only by a service that sends no tenant content (usage). */
    REQUIRED,
}

/** The restriction on one section, and who set it. */
data class SharingRestrictionView(
    val section: String,
    val restriction: SharingRestriction,
    val setAt: OffsetDateTime,
    val setBy: String?,
)

/** A service that sends tenant content cannot be Required: content needs the tenant's own opt-in (ADR 0050 §10). */
class SharingCannotBeRequiredException(section: String) : IllegalArgumentException("$section cannot be required: it sends tenant content, which needs the tenant's own opt-in")

/** A tenant tried to share what the installation does not allow. */
class SharingNotAllowedException(section: String) : IllegalStateException("This installation does not allow sharing $section with Epistola")

/**
 * Reads restrictions inside a handler's own transaction, so a command that would share refuses in
 * the same transaction that would have written the consent.
 */
object SharingRestrictions {
    /** What a tenant reads where it cannot opt in. */
    const val NOT_ALLOWED_REASON = "Not allowed in this installation. An installation administrator decides this."

    fun of(handle: Handle, section: String, connectionId: UUID): SharingRestriction? = handle
        .createQuery("SELECT restriction FROM support_sharing_restriction WHERE connection_id = :connection AND section = :section")
        .bind("connection", connectionId)
        .bind("section", section)
        .mapTo(String::class.java)
        .findOne()
        .map(SharingRestriction::valueOf)
        .orElse(null)

    fun isNotAllowed(handle: Handle, section: String, connectionId: UUID): Boolean = of(handle, section, connectionId) == SharingRestriction.NOT_ALLOWED

    /** Throws [SharingNotAllowedException] while [section] is not allowed towards [connectionId]. */
    fun requireAllowed(handle: Handle, section: String, connectionId: UUID) {
        if (isNotAllowed(handle, section, connectionId)) throw SharingNotAllowedException(section)
    }
}

/** Every section's restriction, by section key; a section without one is absent. */
data class SharingRestrictionsResult(val bySection: Map<String, SharingRestrictionView>) {
    fun of(section: String): SharingRestriction? = bySection[section]?.restriction
}

/** The restrictions towards [connectionId], for the platform page and every tenant's Support page. */
data class GetSharingRestrictions(val connectionId: UUID) :
    Query<SharingRestrictionsResult>,
    RequiresAuthentication

@Component
class GetSharingRestrictionsHandler(private val jdbi: Jdbi) : QueryHandler<GetSharingRestrictions, SharingRestrictionsResult> {
    override fun handle(query: GetSharingRestrictions): SharingRestrictionsResult = jdbi.withHandle<SharingRestrictionsResult, Exception> { handle ->
        SharingRestrictionsResult(
            handle.createQuery(
                """
                SELECT r.section, r.restriction, r.set_at, u.display_name AS set_by
                FROM support_sharing_restriction r LEFT JOIN users u ON u.id = r.set_by
                WHERE r.connection_id = :connection
                """,
            )
                .bind("connection", query.connectionId)
                .map { rs, _ ->
                    SharingRestrictionView(
                        rs.getString("section"),
                        SharingRestriction.valueOf(rs.getString("restriction")),
                        rs.getObject("set_at", OffsetDateTime::class.java),
                        rs.getString("set_by"),
                    )
                }
                .list()
                .associateBy { it.section },
        )
    }
}

/**
 * Sets or clears ([restriction] null) the restriction on [section]. Making it [SharingRestriction.NOT_ALLOWED]
 * forgets every tenant's choice and the installation default of that section at once, and stops what waits
 * to be sent ([TenantSharingSection.forgetAll]); once allowed again, tenants follow whatever default is set
 * next, or are asked.
 */
data class SetSharingRestriction(
    val section: String,
    val restriction: SharingRestriction?,
    val connectionId: UUID,
) : Command<SharingRestrictionsResult>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

@Component
class SetSharingRestrictionHandler(
    private val jdbi: Jdbi,
    private val sections: ObjectProvider<TenantSharingSection>,
) : CommandHandler<SetSharingRestriction, SharingRestrictionsResult> {
    override fun handle(command: SetSharingRestriction): SharingRestrictionsResult {
        if (command.restriction == SharingRestriction.REQUIRED && sections.orderedStream().noneMatch { it.key == command.section && it.offersRequired }) {
            throw SharingCannotBeRequiredException(command.section)
        }
        jdbi.useHandle<Exception> { handle ->
            if (command.restriction == null) {
                handle.createUpdate("DELETE FROM support_sharing_restriction WHERE connection_id = :connection AND section = :section")
                    .bind("connection", command.connectionId).bind("section", command.section).execute()
            } else {
                handle.createUpdate(
                    """
                    INSERT INTO support_sharing_restriction (connection_id, section, restriction, set_at, set_by)
                    VALUES (:connection, :section, :restriction, :now, (SELECT id FROM users WHERE id = :userId))
                    ON CONFLICT (connection_id, section) DO UPDATE SET restriction = EXCLUDED.restriction, set_at = EXCLUDED.set_at, set_by = EXCLUDED.set_by
                    """,
                )
                    .bind("connection", command.connectionId)
                    .bind("section", command.section)
                    .bind("restriction", command.restriction.name)
                    .bind("now", EpistolaClock.offsetDateTime())
                    .bind("userId", SecurityContext.current().userId.value)
                    .execute()
            }
        }
        if (command.restriction == SharingRestriction.NOT_ALLOWED) {
            sections.orderedStream().filter { it.key == command.section }.forEach { it.forgetAll(command.connectionId) }
        }
        return GetSharingRestrictions(command.connectionId).let { GetSharingRestrictionsHandler(jdbi).handle(it) }
    }
}
