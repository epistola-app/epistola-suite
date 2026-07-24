// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.installation

import app.epistola.suite.testing.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstallationServiceIT : IntegrationTestBase() {
    @Autowired
    private lateinit var installations: InstallationService

    @Test
    fun `installation row is seeded by Flyway and readable on every boot`() {
        val installation = installations.get()
        assertNotNull(installation)
        assertNotNull(installation.id)
        assertNotNull(installation.createdAt)
    }

    @Test
    fun `installation id is stable across repeated reads`() {
        val first = installations.get()
        val second = installations.get()
        assertEquals(first, second, "Repeated reads must return the same installation")
    }

    @Test
    fun `seeded createdAt is recent and parsed cleanly`() {
        val installation = installations.get()
        val age = Duration.between(installation.createdAt, Instant.now())
        // Seeded by the V30 migration when the test database was created —
        // should be well under an hour old in any sensible test environment.
        assertTrue(age < Duration.ofHours(1), "createdAt looks stale: $age (${installation.createdAt})")
        assertTrue(age >= Duration.ZERO, "createdAt cannot be in the future: ${installation.createdAt}")
    }
}
