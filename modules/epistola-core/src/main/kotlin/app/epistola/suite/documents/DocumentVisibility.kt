// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents

import app.epistola.suite.common.ids.ApplicationKey
import app.epistola.suite.security.PrincipalApplication
import app.epistola.suite.security.SecurityContext
import org.jdbi.v3.core.statement.SqlStatement

/**
 * Which documents the current caller may see (ADR 0054). An application not marked as seeing every
 * document sees only the requests, documents and results stamped with its id, and — when it is its
 * tenant's owner of them — the unstamped ones, from before applications or from a caller without one. Everyone else
 * sees the whole tenant.
 *
 * Read from the principal, never from the message, so a caller cannot widen it. The predicate is
 * bound into each statement, so sorting, limiting and counting stay in SQL. A query with no principal
 * — background work — is not restricted.
 */
class DocumentVisibility private constructor(
    private val restrictedTo: PrincipalApplication?,
) {
    /**
     * `AND <column> = :visibleApplication` — widened to rows without an application for the owner
     * of unattributed documents — or nothing when the caller sees every document.
     */
    fun predicate(column: String): String = when {
        restrictedTo == null -> ""
        restrictedTo.ownsUnattributedDocuments -> "AND ($column = :$PARAMETER OR $column IS NULL)"
        else -> "AND $column = :$PARAMETER"
    }

    /** Binds the parameter [predicate] names, when it names one. */
    fun <T : SqlStatement<T>> bindTo(statement: T): T = if (restrictedTo == null) statement else statement.bind(PARAMETER, restrictedTo.id)

    companion object {
        private const val PARAMETER = "visibleApplication"

        /** The current caller's visibility. */
        fun current(): DocumentVisibility = DocumentVisibility(SecurityContext.currentOrNull()?.documentsRestrictedTo())

        /** The application a request submitted now is stamped with; null for a caller without one. */
        fun submittingApplication(): ApplicationKey? = SecurityContext.currentOrNull()?.application?.id
    }
}
