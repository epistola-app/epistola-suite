package app.epistola.suite.generation.collect

import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The global result sequence is seeded from wall-clock epoch-MILLISECONDS at
 * (re)initialisation so it stays monotonic across a destructive DB reset — see
 * `V20260616204832__core_generation_results_seq_timeseed.sql`. This guards the
 * invariant that an external consumer's surviving high-water cursor is always
 * below newly emitted results after a reset (a fresh BIGSERIAL would restart at 1
 * and the consumer would silently stop receiving results).
 *
 * The test relies on Flyway having applied the seed migration to the test
 * database, exactly as it would on a real fresh install or post-`clean` rebuild.
 */
class GenerationResultSequenceTimeSeedIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `result sequence is seeded to epoch-millis magnitude, not restarted at 1`() {
        val lastValue = jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery("SELECT last_value FROM generation_results_sequence_seq")
                .mapTo(Long::class.java)
                .one()
        }

        // Floor: 2024-01-01T00:00:00Z in epoch-millis. Any real test run is well
        // after this, so a sequence that restarted at 1 (the bug) fails here.
        val floorMillis = 1_704_067_200_000L
        // Ceiling: ~year 2286 in epoch-millis. Catches an accidental microsecond
        // seed (~1.7e15), which would blow past JS's 2^53 safe-integer limit.
        val ceilingMillis = 10_000_000_000_000L

        assertThat(lastValue)
            .`as`("generation result sequence seeded to epoch-millis magnitude (monotonic across reset)")
            .isBetween(floorMillis, ceilingMillis)
    }
}
