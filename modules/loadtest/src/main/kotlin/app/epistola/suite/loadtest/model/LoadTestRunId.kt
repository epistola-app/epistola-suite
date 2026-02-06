package app.epistola.suite.loadtest.model

import app.epistola.suite.common.UUIDv7
import app.epistola.suite.common.ids.UuidId
import java.util.UUID

/**
 * Typed ID for LoadTestRun entities.
 * Uses UUIDv7 for time-ordered unique identifiers.
 */
@JvmInline
value class LoadTestRunId(override val value: UUID) : UuidId<LoadTestRunId> {
    companion object {
        fun generate(): LoadTestRunId = LoadTestRunId(UUIDv7.generate())
        fun of(value: UUID): LoadTestRunId = LoadTestRunId(value)
    }

    override fun toString(): String = value.toString()
}
