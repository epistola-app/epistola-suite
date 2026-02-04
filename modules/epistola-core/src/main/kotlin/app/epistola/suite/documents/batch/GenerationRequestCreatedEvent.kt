package app.epistola.suite.documents.batch

import app.epistola.suite.documents.model.DocumentGenerationRequest

/**
 * Event published when a document generation request is created.
 *
 * Used by [SynchronousGenerationListener] in test mode to execute jobs immediately
 * instead of waiting for the [JobPoller].
 */
data class GenerationRequestCreatedEvent(
    val request: DocumentGenerationRequest,
)
