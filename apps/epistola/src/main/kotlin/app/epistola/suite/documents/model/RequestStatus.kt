package app.epistola.suite.documents.model

/**
 * Status of a document generation request.
 */
enum class RequestStatus {
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
     * Request was cancelled by user.
     */
    CANCELLED
}
