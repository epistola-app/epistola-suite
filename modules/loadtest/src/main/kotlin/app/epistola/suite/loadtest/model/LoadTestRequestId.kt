package app.epistola.suite.loadtest.model

import app.epistola.suite.common.UUIDv7
import app.epistola.suite.common.ids.UuidId
import java.util.UUID

/**
 * Typed ID for LoadTestRequest entities.
 * Uses UUIDv7 for time-ordered unique identifiers.
 */
@JvmInline
value class LoadTestRequestId(override val value: UUID) : UuidId<LoadTestRequestId> {
    companion object {
        fun generate(): LoadTestRequestId = LoadTestRequestId(UUIDv7.generate())
        fun of(value: UUID): LoadTestRequestId = LoadTestRequestId(value)
    }

    override fun toString(): String = value.toString()
}
