// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.model

/**
 * Status of an individual document generation item in a batch.
 */
enum class ItemStatus {
    /**
     * Item waiting to be processed.
     */
    PENDING,

    /**
     * Item is currently being processed.
     */
    IN_PROGRESS,

    /**
     * Item completed successfully, document generated.
     */
    COMPLETED,

    /**
     * Item processing failed.
     */
    FAILED,
}
