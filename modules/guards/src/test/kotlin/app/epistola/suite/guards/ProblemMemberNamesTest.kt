// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A REST problem names what it is about as contract 2.0 names it everywhere: a resource by its key
 * (`tenantKey`, `catalogKey`, `templateKey`, …, as `ForbiddenProblemDetail` names `tenantKey`), and
 * only what really is an id by `…Id`. A member a client reads cannot be renamed within 2.x, so a
 * key sent under an id's name would be kept for the whole major.
 */
class ProblemMemberNamesTest {
    /** Members whose value is an id, not a key: a request, a document, a release, a catalog version. */
    private val ids = setOf("requestId", "documentId", "releaseId", "versionId")

    private val member = Regex(""""([a-z][A-Za-z]*Id)"\s+to\b""")

    @Test
    fun `problem members name keys as keys`() {
        val files = RepoSources.mainKotlinFiles().filter {
            val path = RepoSources.relativize(it)
            path.startsWith("modules/rest-api/") && path.substringAfterLast('/').startsWith("ApiException")
        }
        assertTrue(files.size >= 2, "ApiExceptionMappings/ApiExceptionHandler not found — the scan would pass vacuously")

        val offenders = files.flatMap { file ->
            member.findAll(RepoSources.stripComments(Files.readString(file)))
                .map { it.groupValues[1] }
                .filter { it !in ids }
                .map { "${RepoSources.relativize(file)}: $it" }
        }.distinct()

        assertEquals(emptyList(), offenders, "Name a key `…Key` in a problem (contract 2.0); `…Id` only for $ids")
    }
}
