// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Pins how a SQL array reaches a Kotlin `List` field.
 *
 * [JdbiConfig] registers this mapper on the *erased* `List`, so it is reachable by every
 * JDBI-mapped constructor parameter of list type in the application while only two columns
 * motivated it. That breadth is not a preference — a narrower `GenericType<List<String>>`
 * registration was tried, and `ExchangeTenantConnection` then fails to map at all — but it does
 * mean the behaviour deserves writing down rather than rediscovering from a failure.
 *
 * Queried as literals rather than through a fixture on purpose: the subject is the type mapping,
 * not any table, and a literal names the SQL type under test right in the assertion.
 */
class JdbiListColumnMapperIT : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    private data class Labelled(val labels: List<String>)

    @Test
    fun `a text array becomes a List of String`() {
        val row =
            jdbi.withHandle<Labelled, Exception> { handle ->
                handle.createQuery("SELECT ARRAY['read', 'publish']::text[] AS labels").mapTo<Labelled>().one()
            }

        assertThat(row.labels).containsExactly("read", "publish")
    }

    @Test
    fun `an empty array becomes an empty list, not null`() {
        val row =
            jdbi.withHandle<Labelled, Exception> { handle ->
                handle.createQuery("SELECT ARRAY[]::text[] AS labels").mapTo<Labelled>().one()
            }

        assertThat(row.labels).isEmpty()
    }

    @Test
    fun `a varchar array is still a List of String`() {
        // `scopes` and `namespaces` are declared VARCHAR(n)[], so this is the shape that ships.
        val row =
            jdbi.withHandle<Labelled, Exception> { handle ->
                handle.createQuery("SELECT ARRAY['acme', 'acme-test']::varchar(63)[] AS labels").mapTo<Labelled>().one()
            }

        assertThat(row.labels).containsExactly("acme", "acme-test")
    }
}
