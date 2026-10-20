// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.suite.validation.ValidationException
import org.springframework.http.ResponseEntity

/**
 * Optimistic locking on the REST API (contract: `ETag` / `If-Match`). A resource's ETag is its
 * revision ([app.epistola.suite.common.Revisions]) as a strong, quoted decimal: `"7"`.
 */
internal object ETags {
    /** The ETag of [revision]. */
    fun of(revision: Long): String = "\"$revision\""

    /**
     * The revision an `If-Match` header names, or null when there is none (or `*`, any revision):
     * then the write proceeds whatever the resource is at. A value that is not one of our ETags is a
     * client error rather than a silent overwrite.
     */
    fun expected(ifMatch: String?): Long? {
        val value = ifMatch?.trim()?.takeIf { it.isNotEmpty() && it != "*" } ?: return null
        return value.removePrefix("W/").removeSurrounding("\"").toLongOrNull()
            ?: throw ValidationException("If-Match", "If-Match must be an ETag this API returned, such as \"7\"")
    }
}

/** A 200 carrying [body] and the ETag of [revision]. */
internal fun <T : Any> okWithETag(revision: Long, body: T): ResponseEntity<T> = ResponseEntity.ok().eTag(ETags.of(revision)).body(body)
