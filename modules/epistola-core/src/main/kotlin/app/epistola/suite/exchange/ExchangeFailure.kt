// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

/**
 * Why a connection or a publication is not progressing, as data rather than as a sentence.
 *
 * The sentence is composed where it is read; see ADR 0017. Recording prose at the moment of failure —
 * inside a `SKIP LOCKED` worker — put the transport's own wording in front of people, meant a copy
 * fix reached only failures that had not happened yet, and left a code and a detail concatenated
 * into one string that had to be split by eye.
 *
 * [message] is what a person is told. The value that makes it specific comes from the row it is
 * rendered beside — the namespace, the version, the attempt count are all columns one place to the
 * left — which is why this carries no parameters of its own.
 */
enum class ExchangeFailureCode(val message: String) {
    /** Exchange does not recognise the application at all; reconnecting alone will not fix it. */
    APPLICATION_UNKNOWN(
        "Exchange no longer recognises this installation's application. Connect again and select " +
            "'Recover application credentials and revoke its previous tokens' during authorization.",
    ),

    /** Exchange refused the stored credentials; a reconnect restores them. */
    CREDENTIALS_REJECTED(
        "Exchange rejected this tenant's stored credentials. Reconnect to restore the connection; " +
            "queued publications resume where they left off.",
    ),

    /** The refresh token itself was refused, which only a fresh authorization repairs. */
    REFRESH_TOKEN_REJECTED("Exchange rejected this tenant's refresh token."),

    /** Exchange refused the connection rather than one catalog's namespace. */
    CONNECTION_REFUSED("Exchange refused this connection."),

    /** The tenant's publishing feature is off, so its queue is paused rather than failing. */
    FEATURE_PAUSED("Catalog publishing is turned off for this tenant; this release is waiting for it to be enabled."),

    /** Nothing to publish through: the tenant has no usable enrollment. */
    NO_ACTIVE_CONNECTION("This tenant has no active Exchange connection."),

    /** Enrolled, but the connection cannot currently produce a token. */
    NO_ACCESS_TOKEN("The Exchange connection could not produce an access token; reauthorize it."),

    /** The catalog is bound to a namespace the organization has since withdrawn. */
    NAMESPACE_NOT_GRANTED("The Exchange connection no longer grants this catalog's namespace."),

    /** Exchange could not be reached at all, which spends no retry budget. */
    EXCHANGE_UNREACHABLE("Exchange could not be reached."),

    /** Exchange took the submission and never decided it. */
    SUBMISSION_UNDECIDED("Exchange did not decide this submission in time. Check it in Exchange, then retry or withdraw it."),

    /** Exchange refused the release itself; its own words are the detail. */
    REJECTED_BY_EXCHANGE("Exchange refused this release."),

    /**
     * Distinct from [REJECTED_BY_EXCHANGE] because it is the one rejection somebody can undo: another
     * installation holds this catalog's publication authority, and an organization administrator can
     * reappoint it. Suite offers that route instead of restating the refusal.
     */
    CATALOG_AUTHORITY_REQUIRED(
        "Another installation is Exchange's appointed publisher for this catalog, so this one may not publish it. " +
            "An organization administrator can reappoint the publisher on Exchange.",
    ),

    /** A transient failure that counts against the retry budget. */
    SUBMISSION_FAILED("The last attempt to publish this release failed."),

    /** Withdrawn by an administrator before Exchange published it. */
    WITHDRAWN("Withdrawn before it was published."),

    /** The connection went away while this release was still queued. */
    CONNECTION_DISCONNECTED("The Exchange connection was disconnected before this release was accepted."),
    ;

    companion object {
        /**
         * Reads a stored code back, tolerating one this version does not know.
         *
         * A row written by a newer Suite must not break an older one's page: an unrecognised code
         * renders as its detail alone, which is worse than a sentence and far better than an error.
         */
        fun parse(value: String?): ExchangeFailureCode? = value?.let { code -> entries.firstOrNull { it.name == code } }
    }
}

/**
 * A recorded failure, ready to render: what happened, and what the far side said about it.
 *
 * [detail] is kept because the remote's own wording is the first thing an operator wants when a
 * refusal is more specific than its code, and shown as supporting detail rather than as the
 * explanation — being the explanation is what made it unreadable.
 */
data class ExchangeFailure(
    val code: ExchangeFailureCode?,
    val detail: String?,
) {
    /** What a person is told: the code's sentence, or the raw detail when the code is unknown. */
    val message: String? get() = code?.message ?: detail

    /** Shown beneath [message], and only when it adds something the sentence does not already say. */
    val supportingDetail: String? get() = detail?.takeIf { code != null }

    /**
     * Whether the fix is on Exchange rather than here.
     *
     * Named for the remedy rather than the code so a template asks "can this be resolved by
     * reappointing the publisher?" instead of comparing an enum name in markup.
     */
    val needsAuthorityTransfer: Boolean get() = code == ExchangeFailureCode.CATALOG_AUTHORITY_REQUIRED

    companion object {
        fun of(code: String?, detail: String?): ExchangeFailure? = if (code == null && detail == null) null else ExchangeFailure(ExchangeFailureCode.parse(code), detail)
    }
}
