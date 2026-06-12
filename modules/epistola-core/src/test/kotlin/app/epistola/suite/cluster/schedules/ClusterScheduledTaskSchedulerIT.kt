package app.epistola.suite.cluster.schedules

import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
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

    @BeforeEach
    fun reset() {
        testClock.reset()
        handler.handled.clear()
        handler.failTaskKeys.clear()
        withMediator {
            DisableClusterScheduledTask("scheduler-missing-handler").execute()
            DisableClusterScheduledTask("scheduler-success").execute()
            DisableClusterScheduledTask("scheduler-failure").execute()
            DisableClusterScheduledTask("scheduler-each-node").execute()
            DisableClusterScheduledTask("scheduler-virtual-time").execute()
        }
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
    fun `poll dispatches an each capable node scheduled task`() {
        withMediator {
            UpsertClusterScheduledTask(
                ClusterScheduledTaskDefinition(
                    taskKey = "scheduler-each-node",
                    routingKey = "system:scheduler-each-node",
                    taskType = RecordingClusterScheduledTaskHandler.TYPE,
                    schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
                    executionScope = ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE,
                ),
            ).execute()
        }
        testClock.advanceBy(Duration.ofSeconds(61))

        scheduler.poll()

        val nodeState = registry.listNodeStates().single { it.taskKey == "scheduler-each-node" }
        assertThat(handler.handled).containsExactly("scheduler-each-node")
        assertThat(nodeState.nextDueAt).isAfter(now())
        assertThat(nodeState.consecutiveFailures).isZero()
    }

    @Test
    fun `advanceTimeBy runs a task that became due in the advanced window`() {
        withMediator {
            UpsertClusterScheduledTask(
                ClusterScheduledTaskDefinition(
                    taskKey = "scheduler-virtual-time",
                    routingKey = "system:scheduler-virtual-time",
                    taskType = RecordingClusterScheduledTaskHandler.TYPE,
                    schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
                ),
            ).execute()
        }

        // Not yet due: moving time without firing keeps it pending.
        testClock.advanceBy(Duration.ofSeconds(30))
        assertThat(handler.handled).isEmpty()

        // Virtual time: advance past due and run everything that became due.
        scheduling.advanceTimeBy(Duration.ofSeconds(31))

        val task = registry.find("scheduler-virtual-time")
        assertThat(handler.handled).containsExactly("scheduler-virtual-time")
        assertThat(task?.leaseOwnerNodeId).isNull()
        assertThat(task?.nextDueAt).isAfter(now())
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

    @Test
    fun `poll records failure when no scheduled task handler is registered`() {
        withMediator {
            UpsertClusterScheduledTask(
                ClusterScheduledTaskDefinition(
                    taskKey = "scheduler-missing-handler",
                    routingKey = "system:scheduler-missing-handler",
                    taskType = "missing-handler",
                    schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
                ),
            ).execute()
        }
        testClock.advanceBy(Duration.ofSeconds(61))

        scheduler.poll()

        val task = registry.find("scheduler-missing-handler")
        assertThat(handler.handled).isEmpty()
        assertThat(task?.leaseOwnerNodeId).isNull()
        assertThat(task?.consecutiveFailures).isEqualTo(1)
        assertThat(task?.lastError).isEqualTo("No handler registered for scheduled task type 'missing-handler'")
        assertThat(task?.nextDueAt).isEqualTo(now().plusSeconds(30))
    }

    private fun seedDueTask(taskKey: String) {
        withMediator {
            UpsertClusterScheduledTask(
                ClusterScheduledTaskDefinition(
                    taskKey = taskKey,
                    routingKey = "system:$taskKey",
                    taskType = RecordingClusterScheduledTaskHandler.TYPE,
                    schedule = ClusterScheduledTaskSchedule.FixedRate(60_000),
                ),
            ).execute()
        }
        testClock.advanceBy(Duration.ofSeconds(61))
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
