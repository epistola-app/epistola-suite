package app.epistola.suite.testing

import app.epistola.suite.documents.batch.DocumentGenerationExecutor
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.storage.ContentStore
import org.jdbi.v3.core.Jdbi
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import tools.jackson.databind.ObjectMapper

/**
 * Test configuration that provides a fake executor instead of the real one.
 *
 * This makes JobPoller use fake PDF generation, speeding up tests dramatically.
 */
@TestConfiguration
class FakeExecutorTestConfiguration {

    @Bean
    @Primary // Override the real DocumentGenerationExecutor
    fun documentGenerationExecutor(
        jdbi: Jdbi,
        generationService: GenerationService,
        mediator: Mediator,
        objectMapper: ObjectMapper,
        contentStore: ContentStore,
        @Value("\${epistola.generation.jobs.retention-days:7}") retentionDays: Int,
        @Value("\${epistola.generation.documents.max-size-mb:50}") maxDocumentSizeMb: Long,
    ): DocumentGenerationExecutor = FakeDocumentGenerationExecutor(
        jdbi,
        generationService,
        mediator,
        objectMapper,
        contentStore,
        retentionDays,
        maxDocumentSizeMb,
    )
}
