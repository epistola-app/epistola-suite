// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import java.time.OffsetDateTime

/**
 * Answers one question about a subscribed catalog: what is its source offering right now?
 *
 * Deliberately narrow. A catalog subscribed from a manifest URL and one installed from Epistola
 * Exchange differ in how they are asked, not in what the answer means, so this is where they meet —
 * and only here. Installing is not shared: walking a manifest and importing an archive are
 * different engines, and an interface spanning both would have to be lossy enough to be useless.
 *
 * The seam also settles a dependency question. The catalog domain must not reference the Exchange
 * integration (`CatalogExchangeIndependenceTest` enforces it in bytecode), so Exchange contributes
 * an implementation of an interface this package owns rather than being called from it. Nothing here
 * knows Exchange exists; a probe claims the sources it recognises by scheme.
 */
interface CatalogUpstreamProbe {
    /** Whether this probe can answer for [catalog], decided from its `sourceUrl` scheme. */
    fun supports(catalog: Catalog): Boolean

    /**
     * What [catalog]'s source currently offers.
     *
     * Throws [CatalogUpstreamCheckException] for anything a caller should record and move past —
     * an unreachable source, a catalog the source no longer publishes, credentials it will not
     * accept. Translating those is the probe's job, because only it knows what its own transport's
     * failures mean.
     */
    fun probe(catalog: Catalog): CatalogUpstreamState
}

/**
 * The last answer a source gave.
 *
 * Several fields are nullable because sources differ in what they are willing to say, and a probe
 * inventing a value would be worse than an absent one: Exchange serves an archive and publishes no
 * wire schema version, so a version mismatch there can only be discovered at import.
 */
data class CatalogUpstreamState(
    val availableVersion: String,
    val availableFingerprint: String? = null,
    val availableSchemaVersion: Int? = null,
    val availablePublishedAt: OffsetDateTime? = null,
    /**
     * Whether the release this catalog is *installed on* is still offered.
     *
     * Null where the source has no such concept — a manifest describes only its current release and
     * says nothing about older ones. A withdrawn release keeps working locally, so this is reported
     * rather than acted on.
     */
    val installedAvailability: UpstreamAvailability? = null,
)

/** What a source says about a particular release, in the only three states that change what we do. */
enum class UpstreamAvailability { AVAILABLE, BLOCKED, WITHDRAWN }

/**
 * Why a check did not produce an answer.
 *
 * Source-agnostic on purpose: this is what gets stored and rendered, so it must mean the same thing
 * whether the source was a URL or Exchange. Probes map their own transport's failures into it —
 * `ExchangeFailureCode` lives in the Exchange integration and cannot be referenced from here.
 */
enum class CatalogUpstreamCheckFailure(val message: String) {
    UNREACHABLE("The source could not be reached."),
    NOT_FOUND("The source no longer publishes this catalog."),
    UNAUTHORIZED("The source refused the credentials for this catalog."),
    SCHEMA_TOO_NEW("The source publishes a catalog format this version of Epistola cannot read."),
    WITHDRAWN("The installed release has been withdrawn by its publisher."),
    PAUSED("Checks are not running for this catalog."),
    PROTOCOL_ERROR("The source answered with something this version of Epistola could not use."),
}

/**
 * A check that failed for a reason worth recording rather than raising.
 *
 * [detail] is whatever the far side said, kept separate from the code so the wording shown to a
 * reader can be improved later for rows already written. Same rule as ADR 0017.
 */
class CatalogUpstreamCheckException(
    val failure: CatalogUpstreamCheckFailure,
    val detail: String? = null,
    cause: Throwable? = null,
) : RuntimeException(failure.message, cause)
