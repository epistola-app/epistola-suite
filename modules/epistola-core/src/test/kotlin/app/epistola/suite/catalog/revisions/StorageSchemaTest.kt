// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The content-addressed storage under the names ADR 0033 gives it: `release_resources` points at
 * `resource_contents` by content hash, through a foreign key and an index, and `resource_binaries`
 * links content to its bytes. The schema is read from the catalog, since no command can show it.
 */
class StorageSchemaTest : IntegrationTestBase() {
    @Autowired private lateinit var jdbi: Jdbi

    private fun list(sql: String): List<String> = jdbi.withHandle<List<String>, Exception> { it.createQuery(sql).mapTo(String::class.java).list() }

    @Test
    fun `the storage tables have their ADR 0033 names, and the old ones are gone`() {
        val tables = list("SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()")
        assertThat(tables).contains("release_resources", "resource_contents", "resource_binaries", "resource_references")
            .doesNotContain("release_entries", "resource_revisions", "revision_binaries")
    }

    @Test
    fun `a release's resource points at its content by content hash, through a foreign key and an index`() {
        val columns = list("SELECT column_name FROM information_schema.columns WHERE table_name = 'release_resources'")
        assertThat(columns).contains("content_hash").doesNotContain("revision_digest")
        assertThat(list("SELECT conname FROM pg_constraint WHERE conrelid = 'release_resources'::regclass AND contype = 'f'")).contains("fk_release_resources_content")
        assertThat(list("SELECT indexdef FROM pg_indexes WHERE tablename = 'release_resources'")).anyMatch { it.contains("(tenant_key, content_hash)") }
        assertThat(list("SELECT column_name FROM information_schema.columns WHERE table_name = 'resource_binaries'")).contains("content_hash", "binary_hash")
    }
}
