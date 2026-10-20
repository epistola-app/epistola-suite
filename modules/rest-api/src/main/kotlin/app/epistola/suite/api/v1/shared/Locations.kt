// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.net.URI

/**
 * `Location` on every create (contract): a 201 carries the address of what it created, which is the
 * collection the request posted to followed by the new resource's [key].
 */
internal fun <T : Any> created(key: String, body: T): ResponseEntity<T> = ResponseEntity.created(inCurrentCollection(key)).body(body)

/** The current request's address with [segment] appended: a member of the collection posted to. */
internal fun inCurrentCollection(segment: String): URI = ServletUriComponentsBuilder.fromCurrentRequestUri().pathSegment(segment).build().toUri()

/**
 * A 202 for an accepted generation, carrying the address of its job (contract `getGenerationJobStatus`,
 * `…/documents/jobs/{requestId}`), next to the `…/documents/generate` the request posted to.
 */
internal fun <T : Any> acceptedAsJob(requestId: String, body: T): ResponseEntity<T> {
    val current = ServletUriComponentsBuilder.fromCurrentRequestUri().build().toUri()
    val documents = current.path.substringBefore("/documents/") + "/documents"
    return ResponseEntity.status(HttpStatus.ACCEPTED).location(current.resolve("$documents/jobs/$requestId")).body(body)
}
