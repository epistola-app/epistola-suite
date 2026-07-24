// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.common

import com.github.f4b6a3.uuid.UuidCreator
import java.util.UUID

object UUIDv7 {
    fun generate(): UUID = UuidCreator.getTimeOrderedEpoch()
}
