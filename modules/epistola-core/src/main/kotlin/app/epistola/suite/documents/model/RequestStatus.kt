// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.model

/**
 * Status of a document generation request.
 */
enum class RequestStatus {
    /**
     * Request created with `interactive`, waiting for a person to complete its data in the form
     * (contract ADR 0011). Never claimed: submitting the form moves it to [PENDING].
     */
    AWAITING_INPUT,

    /**
     * Request created, waiting to be processed.
     */
    PENDING,

    /**
     * Request is currently being processed.
     */
    IN_PROGRESS,

    /**
     * Request completed successfully (all items succeeded or batch partially completed).
     */
    COMPLETED,

    /**
     * Request failed (single generation failed or batch job failed to start).
     */
    FAILED,

    /**
     * Request was cancelled; [CancelReason] says by whom.
     */
    CANCELLED,
}

/**
 * Why a request was cancelled (contract `GenerationCancelReason`). Null on requests cancelled before
 * interactive generation existed.
 */
enum class CancelReason {
    /** By `cancelGenerationJob` (or the UI), with a form or without. */
    CALLER,

    /** By the person, on the form. */
    USER,

    /** Nobody submitted the form before it expired. */
    EXPIRED,
}
