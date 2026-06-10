package app.epistola.suite.cluster.schedules

import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList

@TestPropertySource(
    properties = [
        "epistola.cluster.scheduled-tasks.poll-interval-ms=60000",
        "epistola.cluster.scheduled-tasks.lease-duration-ms=30000",
        "epistola.cluster.scheduled-tasks.retry-delay-ms=30000",
    ],
)
@Import(ClusterScheduledTaskSchedulerIT.TaskHandlerConfiguration::class)
class ClusterScheduledTaskSchedulerIT : IntegrationTestBase() {

    @Autowired
    private lateinit var scheduler: ClusterScheduledTaskScheduler

    @Autowired
    private lateinit var registry: ClusterScheduledTaskRegistry

    @Autowired
    private lateinit var handler: RecordingClusterScheduledTaskHandler

    @Autowired
    private lateinit var jdbi: Jdbi

    @BeforeEach
    fun reset() {
        testClock.reset()
        handler.handled.clear()
        handler.failTaskKeys.clear()
        deleteTask("scheduler-success")
        deleteTask("scheduler-failure")
    }

    @Test
    fun `poll dispatches an owned scheduled task and advances recurrence`() {
        seedDueTask("scheduler-success")

        scheduler.poll()

        val task = registry.find("scheduler-success")
        assertThat(handler.handled).containsExactly("scheduler-success")
        assertThat(task?.leaseOwnerNodeId).isNull()
        assertThat(task?.nextDueAt).isAfter(now())
        assertThat(task?.consecutiveFailures).isZero()
    }

    @Test
    fun `poll records failure and retries the same occurrence`() {
        seedDueTask("scheduler-failure")
        handler.failTaskKeys += "scheduler-failure"

        scheduler.poll()

        val task = registry.find("scheduler-failure")
        assertThat(handler.handled).containsExactly("scheduler-failure")
        assertThat(task?.leaseOwnerNodeId).isNull()
        assertThat(task?.consecutiveFailures).isEqualTo(1)
        assertThat(task?.lastError).isEqualTo("planned failure")
        assertThat(task?.nextDueAt).isEqualTo(now().plusSeconds(30))
    }

    private fun seedDueTask(taskKey: String) {
        registry.upsert(
            ClusterScheduledTaskDefinition(
                taskKey = taskKey,
                routingKey = "system:$taskKey",
                taskType = RecordingClusterScheduledTaskHandler.TYPE,
                schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
            ),
        )
        testClock.advanceBy(Duration.ofSeconds(61))
    }

    private fun deleteTask(taskKey: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM cluster_tasks_scheduled WHERE task_key = :taskKey")
                .bind("taskKey", taskKey)
                .execute()
        }
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(testClock)

    @TestConfiguration
    class TaskHandlerConfiguration {
        @Bean
        fun recordingClusterScheduledTaskHandler(): RecordingClusterScheduledTaskHandler = RecordingClusterScheduledTaskHandler()
    }
}

class RecordingClusterScheduledTaskHandler : ClusterScheduledTaskHandler {
    val handled = CopyOnWriteArrayList<String>()
    val failTaskKeys = CopyOnWriteArrayList<String>()
    override val taskType: String = TYPE

    override fun handle(task: ClusterScheduledTask) {
        handled += task.taskKey
        if (task.taskKey in failTaskKeys) {
            throw IllegalStateException("planned failure")
        }
    }

    companion object {
        const val TYPE = "recording-scheduled-test"
    }
}
