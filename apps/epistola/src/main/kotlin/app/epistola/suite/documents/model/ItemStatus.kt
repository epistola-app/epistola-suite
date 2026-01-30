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
    FAILED
}
