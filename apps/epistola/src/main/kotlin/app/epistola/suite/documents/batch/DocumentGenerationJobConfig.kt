package app.epistola.suite.documents.batch

import app.epistola.suite.documents.model.Document
import app.epistola.suite.documents.model.DocumentGenerationItem
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.mediator.Mediator
import org.jdbi.v3.core.Jdbi
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.item.ItemProcessor
import org.springframework.batch.infrastructure.item.ItemReader
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
 *
 * The job uses step-scoped components (reader, processor) that receive job parameters
 * via late binding. The step and listener themselves are not scoped to avoid proxy
 * resolution issues during context initialization.
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
    fun documentGenerationJob(
        generateDocumentsStep: Step,
    ): Job = JobBuilder(JOB_NAME, jobRepository)
        .preventRestart() // Don't allow manual restarts to avoid duplicate processing
        .start(generateDocumentsStep)
        .listener(JobCompletionListener(jdbi, jobRetentionDays))
        .build()

    /**
     * Creates the document generation step.
     *
     * This step uses chunk-oriented processing:
     * - Reader: Fetches pending items and marks as IN_PROGRESS (step-scoped)
     * - Processor: Generates PDF for each item (step-scoped)
     * - Writer: Persists documents and updates item status
     *
     * The step itself is not scoped. Job parameters are accessed by the scoped
     * reader and processor components via late binding.
     */
    @Bean
    fun generateDocumentsStep(
        itemReader: ItemReader<DocumentGenerationItem>,
        itemProcessor: ItemProcessor<DocumentGenerationItem, Document>,
    ): Step = StepBuilder(STEP_NAME, jobRepository)
        .chunk<DocumentGenerationItem, Document>(chunkSize, transactionManager)
        .reader(itemReader)
        .processor(itemProcessor)
        .writer(DocumentGenerationItemWriter(jdbi))
        .faultTolerant()
        .skipLimit(Int.MAX_VALUE) // Allow unlimited skips - each item fails independently
        .skip(Exception::class.java) // Skip any exception - processor marks items as FAILED
        .build()

    /**
     * Creates the item reader for a specific request.
     * Step-scoped to access job parameters at runtime.
     */
    @Bean
    @StepScope
    fun itemReader(
        @Value("#{jobParameters['$PARAM_REQUEST_ID']}") requestId: String,
    ): ItemReader<DocumentGenerationItem> {
        val requestUuid = UUID.fromString(requestId)
        return DocumentGenerationItemReader(jdbi, requestUuid)
    }

    /**
     * Creates the item processor.
     * Step-scoped to ensure proper lifecycle management.
     */
    @Bean
    @StepScope
    fun itemProcessor(): ItemProcessor<DocumentGenerationItem, Document> = DocumentGenerationItemProcessor(
        mediator = mediator,
        generationService = generationService,
        objectMapper = objectMapper,
        jdbi = jdbi,
        maxDocumentSizeMb = maxDocumentSizeMb,
    )
}
