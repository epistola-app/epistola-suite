package app.epistola.suite.documents.model

/**
 * Type of document generation job.
 */
enum class JobType {
    /**
     * Single document generation.
     */
    SINGLE,

    /**
     * Batch document generation with multiple items.
     */
    BATCH,
}
