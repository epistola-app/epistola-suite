// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import app.epistola.suite.templates.validation.MissingDataField
import app.epistola.suite.validation.validate
import java.time.OffsetDateTime

/**
 * When a person is asked to complete a generation request's data (contract
 * `InteractiveGenerationTrigger`).
 */
enum class InteractiveTrigger {
    /** Only when the data, as sent, lacks a required field or holds an invalid value. */
    INCOMPLETE,

    /** Every time, with the form pre-filled for review. */
    ALWAYS,
}

/** A link the form shows: back to the case, to a guideline. */
data class InteractiveLink(val label: String, val url: String) {
    init {
        validate("label", label.isNotBlank() && label.length <= MAX_LINK_LABEL) { "A link's label is 1 to $MAX_LINK_LABEL characters" }
        validate("url", url.length <= MAX_URL && HTTP_URL.matches(url)) { "A link is an http or https URL of at most $MAX_URL characters" }
    }
}

/**
 * How a generation request asks a person to complete its data (contract ADR 0011, the request's
 * `interactive`). Sending it is the opt-in. The limits are the contract's, checked here as well so
 * the UI path is held to them too.
 *
 * @property expiresInMinutes How long the link stays usable; null for the installation's default.
 *   Above the installation's maximum the request is refused (checked by the handler, which knows it).
 * @property returnUrl Where the form sends the browser after submitting or cancelling.
 * @property title A heading for the form; plain text.
 * @property instructions Text shown above the fields; plain text, line breaks kept.
 * @property locale A BCP 47 tag for the form's own text; null for the tenant's default locale.
 */
data class InteractiveGeneration(
    val trigger: InteractiveTrigger,
    val expiresInMinutes: Int? = null,
    val returnUrl: String? = null,
    val title: String? = null,
    val instructions: String? = null,
    val links: List<InteractiveLink> = emptyList(),
    val locale: String? = null,
) {
    init {
        validate("interactive.expiresInMinutes", expiresInMinutes == null || expiresInMinutes in 1..CONTRACT_MAX_EXPIRY_MINUTES) {
            "The link's expiry is 1 to $CONTRACT_MAX_EXPIRY_MINUTES minutes"
        }
        validate("interactive.returnUrl", returnUrl == null || (returnUrl.length <= MAX_URL && HTTP_URL.matches(returnUrl))) {
            "The return URL is an http or https URL of at most $MAX_URL characters"
        }
        validate("interactive.title", title == null || title.length <= MAX_TITLE) { "The title is at most $MAX_TITLE characters" }
        validate("interactive.instructions", instructions == null || instructions.length <= MAX_INSTRUCTIONS) {
            "The instructions are at most $MAX_INSTRUCTIONS characters"
        }
        validate("interactive.links", links.size <= MAX_LINKS) { "At most $MAX_LINKS links" }
        validate("interactive.locale", locale == null || (locale.length <= MAX_LOCALE && LOCALE.matches(locale))) {
            "The locale is a BCP 47 language tag"
        }
    }

    /** Every URL the request names, by the field a refusal is reported on. */
    fun urls(): List<Pair<String, String>> = listOfNotNull(returnUrl?.let { "interactive.returnUrl" to it }) +
        links.mapIndexed { i, link -> "interactive.links[$i].url" to link.url }

    companion object {
        /** No job waits for input longer than 30 days, on any server (contract ADR 0011). */
        const val CONTRACT_MAX_EXPIRY_MINUTES = 43_200
        const val MAX_URL = 2048
        const val MAX_TITLE = 200
        const val MAX_INSTRUCTIONS = 4000
        const val MAX_LINKS = 5
        const val MAX_LOCALE = 35
    }
}

/**
 * The form of a request created with `interactive`, as the caller sees it (contract
 * `InteractiveGenerationDto` plus the item's `editedFields`).
 *
 * @property url Where to send the person's browser; set only while the request is awaiting input.
 * @property expiresAt When the link stops working; null when nothing was asked.
 * @property missingFields What the form asks for; empty when nothing was asked.
 * @property editedFields JSON Pointers where the data differs from what the caller sent; null while
 *   the request is awaiting input, and set (possibly empty) once it is submitted or when nothing was
 *   asked.
 */
data class InteractiveState(
    val trigger: InteractiveTrigger,
    val url: String?,
    val expiresAt: OffsetDateTime?,
    val missingFields: List<MissingDataField>,
    val editedFields: List<String>?,
)

private const val MAX_LINK_LABEL = 100
private const val MAX_URL = InteractiveGeneration.MAX_URL
private val HTTP_URL = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
private val LOCALE = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*$")
