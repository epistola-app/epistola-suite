package app.epistola.suite.cluster.timers

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

    @Autowired
    private lateinit var jdbi: Jdbi

    @BeforeEach
    fun reset() {
        testClock.reset()
        handler.handled.clear()
        deleteTimer("scheduler-complete")
        deleteTimer("scheduler-reschedule")
    }

    @Test
    fun `poll dispatches an owned timer and completes it`() {
        registry.schedule("scheduler-complete", "tenant-a", RecordingClusterTimerHandler.TYPE, now().plusMinutes(1))
        testClock.advanceBy(Duration.ofSeconds(61))

        scheduler.poll()

        assertThat(handler.handled).containsExactly("scheduler-complete")
        assertThat(registry.find("scheduler-complete")).isNull()
    }

    @Test
    fun `poll can reschedule a recurring timer`() {
        registry.schedule(
            timerKey = "scheduler-reschedule",
            routingKey = "tenant-a",
            timerType = RecordingClusterTimerHandler.TYPE,
            dueAt = now().plusMinutes(1),
            payload = mapOf("mode" to "reschedule"),
        )
        testClock.advanceBy(Duration.ofSeconds(61))

        scheduler.poll()

        val timer = registry.find("scheduler-reschedule")
        assertThat(handler.handled).containsExactly("scheduler-reschedule")
        assertThat(timer?.status).isEqualTo(ClusterTimerStatus.SCHEDULED)
        assertThat(timer?.dueAt).isAfter(now())
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(testClock)

    private fun deleteTimer(timerKey: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM cluster_timers WHERE timer_key = :timerKey")
                .bind("timerKey", timerKey)
                .execute()
        }
    }

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
