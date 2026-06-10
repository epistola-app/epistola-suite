package app.epistola.suite.cluster.timers

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
        "epistola.cluster.timers.poll-interval-ms=60000",
        "epistola.cluster.timers.lease-duration-ms=30000",
        "epistola.cluster.timers.retry-delay-ms=30000",
    ],
)
@Import(ClusterTimerSchedulerIT.TimerHandlerConfiguration::class)
class ClusterTimerSchedulerIT : IntegrationTestBase() {

    @Autowired
    private lateinit var scheduler: ClusterTimerScheduler

    @Autowired
    private lateinit var registry: ClusterTimerRegistry

    @Autowired
    private lateinit var handler: RecordingClusterTimerHandler

    @BeforeEach
    fun reset() {
        testClock.reset()
        handler.handled.clear()
        withMediator {
            CancelClusterTimer("scheduler-complete").execute()
            CancelClusterTimer("scheduler-missing-handler").execute()
            CancelClusterTimer("scheduler-reschedule").execute()
        }
    }

    @Test
    fun `poll dispatches an owned timer and completes it`() {
        withMediator {
            ScheduleClusterTimer("scheduler-complete", "tenant-a", RecordingClusterTimerHandler.TYPE, now().plusMinutes(1)).execute()
        }
        testClock.advanceBy(Duration.ofSeconds(61))

        scheduler.poll()

        assertThat(handler.handled).containsExactly("scheduler-complete")
        assertThat(registry.find("scheduler-complete")).isNull()
    }

    @Test
    fun `poll can reschedule a recurring timer`() {
        withMediator {
            ScheduleClusterTimer(
                timerKey = "scheduler-reschedule",
                routingKey = "tenant-a",
                timerType = RecordingClusterTimerHandler.TYPE,
                dueAt = now().plusMinutes(1),
                payload = mapOf("mode" to "reschedule"),
            ).execute()
        }
        testClock.advanceBy(Duration.ofSeconds(61))

        scheduler.poll()

        val timer = registry.find("scheduler-reschedule")
        assertThat(handler.handled).containsExactly("scheduler-reschedule")
        assertThat(timer?.status).isEqualTo(ClusterTimerStatus.SCHEDULED)
        assertThat(timer?.dueAt).isAfter(now())
    }

    @Test
    fun `poll retries timer when no handler is registered`() {
        withMediator {
            ScheduleClusterTimer(
                timerKey = "scheduler-missing-handler",
                routingKey = "tenant-a",
                timerType = "missing-handler",
                dueAt = now().plusMinutes(1),
            ).execute()
        }
        testClock.advanceBy(Duration.ofSeconds(61))

        scheduler.poll()

        val timer = registry.find("scheduler-missing-handler")
        assertThat(handler.handled).isEmpty()
        assertThat(timer?.status).isEqualTo(ClusterTimerStatus.SCHEDULED)
        assertThat(timer?.leaseOwnerNodeId).isNull()
        assertThat(timer?.attemptCount).isEqualTo(1)
        assertThat(timer?.lastError).isEqualTo("No handler registered for timer type 'missing-handler'")
        assertThat(timer?.dueAt).isEqualTo(now().plusSeconds(30))
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(testClock)

    @TestConfiguration
    class TimerHandlerConfiguration {
        @Bean
        fun recordingClusterTimerHandler(): RecordingClusterTimerHandler = RecordingClusterTimerHandler()
    }
}

class RecordingClusterTimerHandler : ClusterTimerHandler {
    val handled = CopyOnWriteArrayList<String>()
    override val timerType: String = TYPE

    override fun handle(timer: ClusterTimer): ClusterTimerResult {
        handled += timer.timerKey
        if (timer.payload["mode"] == "reschedule") {
            return ClusterTimerResult.Reschedule(timer.dueAt.plusMinutes(5))
        }
        return ClusterTimerResult.Complete
    }

    companion object {
        const val TYPE = "recording-test"
    }
}
