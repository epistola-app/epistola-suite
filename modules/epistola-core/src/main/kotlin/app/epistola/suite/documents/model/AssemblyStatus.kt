package app.epistola.suite.documents.model

/**
 * Status of batch download assembly (ZIP/merged PDF creation).
 */
enum class AssemblyStatus {
    /** No download assembly requested. */
    NONE,

    /** Assembly requested, waiting to start. */
    PENDING,

    /** Assembly is currently in progress. */
    IN_PROGRESS,

    /** Assembly completed successfully. */
    COMPLETED,

    /** Assembly failed. */
    FAILED,
}
