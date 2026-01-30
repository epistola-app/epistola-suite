package app.epistola.suite.documents.batch

import app.epistola.suite.documents.model.Document
import app.epistola.suite.documents.model.DocumentGenerationItem
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.mediator.Mediator
import org.jdbi.v3.core.Jdbi
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Spring Batch configuration for document generation jobs.
 *
 * This configuration creates a parameterized job that can be launched with a request ID.
 * Each job execution processes all items for a single generation request.
 */
@Configuration
class DocumentGenerationJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val mediator: Mediator,
    private val generationService: GenerationService,
    private val objectMapper: ObjectMapper,
    private val jdbi: Jdbi,
    @Value("\${epistola.generation.async.chunk-size:10}")
    private val chunkSize: Int,
    @Value("\${epistola.generation.jobs.retention-days:7}")
    private val jobRetentionDays: Int,
    @Value("\${epistola.generation.documents.max-size-mb:50}")
    private val maxDocumentSizeMb: Long,
) {

    companion object {
        const val JOB_NAME = "documentGeneration"
        const val STEP_NAME = "generateDocuments"
        const val PARAM_REQUEST_ID = "requestId"
    }

    /**
     * Creates the document generation job.
     *
     * This job is not auto-started. It must be launched programmatically with a request ID parameter.
     */
    @Bean
    fun documentGenerationJob(): Job {
        return JobBuilder(JOB_NAME, jobRepository)
            .preventRestart() // Don't allow manual restarts to avoid duplicate processing
            .start(generateDocumentsStep(null)) // Step is created per execution
            .listener(jobExecutionListener(null)) // Listener is created per execution
            .build()
    }

    /**
     * Creates the document generation step.
     *
     * This step uses chunk-oriented processing:
     * - Reader: Fetches pending items and marks as IN_PROGRESS
     * - Processor: Generates PDF for each item
     * - Writer: Persists documents and updates item status
     *
     * @param requestId The generation request ID (injected per job execution)
     */
    @Bean
    fun generateDocumentsStep(
        @Value("#{jobParameters['$PARAM_REQUEST_ID']}") requestId: UUID?,
    ): Step {
        return StepBuilder(STEP_NAME, jobRepository)
            .chunk<DocumentGenerationItem, Document>(chunkSize, transactionManager)
            .reader(itemReader(requestId))
            .processor(itemProcessor())
            .writer(itemWriter())
            .faultTolerant()
            .skipLimit(Int.MAX_VALUE) // Allow unlimited skips - each item fails independently
            .skip(Exception::class.java) // Skip any exception - processor marks items as FAILED
            .build()
    }

    /**
     * Creates the item reader for a specific request.
     */
    private fun itemReader(requestId: UUID?): DocumentGenerationItemReader {
        requireNotNull(requestId) { "Request ID is required for item reader" }
        return DocumentGenerationItemReader(jdbi, requestId)
    }

    /**
     * Creates the item processor.
     */
    private fun itemProcessor(): DocumentGenerationItemProcessor {
        return DocumentGenerationItemProcessor(
            mediator = mediator,
            generationService = generationService,
            objectMapper = objectMapper,
            jdbi = jdbi,
            maxDocumentSizeMb = maxDocumentSizeMb
        )
    }

    /**
     * Creates the item writer.
     */
    private fun itemWriter(): DocumentGenerationItemWriter {
        return DocumentGenerationItemWriter(jdbi)
    }

    /**
     * Creates the job execution listener for job lifecycle events.
     */
    private fun jobExecutionListener(requestId: UUID?): JobCompletionListener {
        requireNotNull(requestId) { "Request ID is required for completion listener" }
        return JobCompletionListener(
            jdbi = jdbi,
            requestId = requestId,
            retentionDays = jobRetentionDays
        )
    }
}
