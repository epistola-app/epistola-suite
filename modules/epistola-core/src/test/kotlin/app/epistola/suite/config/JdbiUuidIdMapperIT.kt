// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.common.ids.ResourceIdentity
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * How a UUID-backed key crosses the JDBI boundary in both directions, and the one route that does
 * not work.
 *
 * [ResourceIdentity] is the key that made this worth pinning — it is bound and read on the
 * relocation and identity paths — but [JdbiConfig] registers the argument factory for every
 * [app.epistola.suite.common.ids.UuidKey], so this is the behaviour of all of them.
 *
 * Queried as literals rather than through a fixture on purpose: the subject is the type mapping,
 * not any table.
 */
class JdbiUuidIdMapperIT : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    private data class Identified(val resourceId: ResourceIdentity)

    private val identity = ResourceIdentity.of(UUID.fromString("01900000-0000-7000-8000-00000000beef"))

    @Test
    fun `a typed key binds to a uuid parameter without being unwrapped`() {
        val matched = jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery("SELECT :identity = CAST(:literal AS uuid) AS matched")
                .bind("identity", identity)
                .bind("literal", identity.value.toString())
                .mapTo(Boolean::class.java)
                .one()
        }

        assertThat(matched)
            .describedAs("UuidIdArgumentFactory should send the key's UUID, not the boxed value class")
            .isTrue()
    }

    /** `FontCatalogWriter` deletes a tenant's faces with `font_resource_id IN (<families>)`. */
    @Test
    fun `a list of typed keys binds through bindList`() {
        val other = ResourceIdentity.generate()
        val matched = jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery("SELECT CAST(:literal AS uuid) IN (<identities>) AS matched")
                .bindList("identities", listOf(other, identity))
                .bind("literal", identity.value.toString())
                .mapTo(Boolean::class.java)
                .one()
        }

        assertThat(matched).isTrue()
    }

    @Test
    fun `a uuid column reaches a constructor parameter typed as the key`() {
        val row = jdbi.withHandle<Identified, Exception> { handle ->
            handle.createQuery("SELECT CAST(:literal AS uuid) AS resource_id")
                .bind("literal", identity.value.toString())
                .mapTo<Identified>()
                .one()
        }

        assertThat(row.resourceId).isEqualTo(identity)
    }

    @Test
    fun `a null uuid column maps to a null key rather than failing`() {
        val row = jdbi.withHandle<Identified?, Exception> { handle ->
            handle.createQuery("SELECT CAST(NULL AS uuid) AS resource_id")
                .map { rs, ctx -> UuidIdColumnMapper(ResourceIdentity::of).map(rs, 1, ctx)?.let(::Identified) }
                .one()
        }

        assertThat(row).isNull()
    }

    /**
     * Why the identity read paths map by hand instead of calling `mapTo`.
     *
     * JDBI's Kotlin plugin claims any Kotlin class for constructor binding before the column-mapper
     * registry is consulted, so a single-column `mapTo` on a value class looks for a `resource_id`
     * *constructor parameter* and fails. Pinned rather than described: if a JDBI upgrade fixes this,
     * this test fails and the hand-written mappers can go.
     */
    @Test
    fun `mapTo on the key itself is claimed by the Kotlin plugin and fails`() {
        assertThatThrownBy {
            jdbi.withHandle<ResourceIdentity, Exception> { handle ->
                handle.createQuery("SELECT CAST(:literal AS uuid) AS resource_id")
                    .bind("literal", identity.value.toString())
                    .mapTo(ResourceIdentity::class.java)
                    .one()
            }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Could not match constructor parameters")
    }
}
