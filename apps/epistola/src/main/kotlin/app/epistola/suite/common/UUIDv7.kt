package app.epistola.suite.common

import com.github.f4b6a3.uuid.UuidCreator
import java.util.UUID

object UUIDv7 {
    fun generate(): UUID = UuidCreator.getTimeOrderedEpoch()
}
