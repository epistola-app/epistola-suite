// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * Extracts a small, curated set of decoded OIDC claim *values* for the profile page's
 * token-debug section.
 *
 * Hard rule (issue #578): the raw token is **never** part of the output — only the
 * explicitly-listed claim values below. There is no path here that reads or returns
 * `OidcIdToken.tokenValue`; callers pass the decoded claims map only.
 *
 * The function is pure (no Spring, no security context) so it can be unit-tested by
 * handing it a plain claims map.
 */
object ProfileTokenClaims {

    /** A single decoded claim rendered for display: the claim name and its human-readable value. */
    data class ClaimRow(val claim: String, val value: String)

    private val TIMESTAMP_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")

    /** Scalar string claims rendered verbatim, in display order. */
    private val SCALAR_CLAIMS = listOf("sub", "preferred_username", "email", "name", "iss", "azp")

    /** Claims that are timestamps (seconds since epoch) and rendered human-readable. */
    private val TIMESTAMP_CLAIMS = listOf("iat", "exp")

    /** Claims that are lists (or single values) rendered as a comma-joined list. */
    private val LIST_CLAIMS = listOf("aud", "groups")

    /**
     * Builds the curated claim rows from the decoded id-token [claims].
     *
     * @param claims the decoded id-token claims (e.g. `oidcUser.idToken.claims`) — never the raw token.
     * @param flatRolesClaimName the configured flat-roles claim name (default `roles`), rendered as a list.
     * @return ordered rows for the curated claims that are present; absent claims are omitted.
     */
    fun extract(claims: Map<String, Any?>, flatRolesClaimName: String): List<ClaimRow> {
        val rows = mutableListOf<ClaimRow>()

        for (name in SCALAR_CLAIMS) {
            claims[name]?.let { rows += ClaimRow(name, it.toString()) }
        }
        for (name in TIMESTAMP_CLAIMS) {
            claims[name]?.let { rows += ClaimRow(name, formatTimestamp(it)) }
        }
        for (name in LIST_CLAIMS) {
            claims[name]?.let { rows += ClaimRow(name, formatList(it)) }
        }
        // The configured flat-roles claim (skip if it collides with a name already rendered above).
        if (flatRolesClaimName !in SCALAR_CLAIMS + TIMESTAMP_CLAIMS + LIST_CLAIMS) {
            claims[flatRolesClaimName]?.let { rows += ClaimRow(flatRolesClaimName, formatList(it)) }
        }

        return rows
    }

    private fun formatList(value: Any?): String = when (value) {
        is Collection<*> -> value.filterNotNull().joinToString(", ") { it.toString() }
        is Array<*> -> value.filterNotNull().joinToString(", ") { it.toString() }
        else -> value?.toString().orEmpty()
    }

    private fun formatTimestamp(value: Any?): String {
        val instant = when (value) {
            is Instant -> value
            is Date -> value.toInstant()
            is Number -> Instant.ofEpochSecond(value.toLong())
            else -> return value?.toString().orEmpty()
        }
        return TIMESTAMP_FORMATTER.format(instant.atOffset(ZoneOffset.UTC))
    }
}
