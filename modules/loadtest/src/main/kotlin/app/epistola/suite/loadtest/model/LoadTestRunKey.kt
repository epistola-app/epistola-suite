package app.epistola.suite.loadtest.model

import app.epistola.suite.common.UUIDv7
import app.epistola.suite.common.ids.UuidKey
import java.util.UUID

/**
 * Typed ID for LoadTestRun entities.
 * Uses UUIDv7 for time-ordered unique identifiers.
 */
@JvmInline
value class LoadTestRunKey(override val value: UUID) : UuidKey<LoadTestRunKey> {
    companion object {
        fun generate(): LoadTestRunKey = LoadTestRunKey(UUIDv7.generate())
        fun of(value: UUID): LoadTestRunKey = LoadTestRunKey(value)
    }

    override fun toString(): String = value.toString()
}
