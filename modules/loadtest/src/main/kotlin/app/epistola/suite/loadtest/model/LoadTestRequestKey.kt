package app.epistola.suite.loadtest.model

import app.epistola.suite.common.UUIDv7
import app.epistola.suite.common.ids.UuidKey
import java.util.UUID

/**
 * Typed ID for LoadTestRequest entities.
 * Uses UUIDv7 for time-ordered unique identifiers.
 */
@JvmInline
value class LoadTestRequestKey(override val value: UUID) : UuidKey<LoadTestRequestKey> {
    companion object {
        fun generate(): LoadTestRequestKey = LoadTestRequestKey(UUIDv7.generate())
        fun of(value: UUID): LoadTestRequestKey = LoadTestRequestKey(value)
    }

    override fun toString(): String = value.toString()
}
