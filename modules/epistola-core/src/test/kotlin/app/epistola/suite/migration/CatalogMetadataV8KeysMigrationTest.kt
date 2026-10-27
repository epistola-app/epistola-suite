// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.DriverManager

/**
 * The 2.0 upgrade (step 1) renames the catalog wire members v8 renamed inside stored catalog
 * metadata, keeping every value: presentation `iconAssetSlug`/`imageAssetSlugs` become
 * `iconImageKey`/`imageKeys`, an attribute assignment's `catalog` becomes `catalogKey`.
 */
@Tag("integration")
class CatalogMetadataV8KeysMigrationTest {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `stored catalog metadata gets the wire v8 member names and keeps its values`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "catalog_metadata_v8_keys_migration"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { admin ->
            admin.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS \"$database\"")
                it.execute("CREATE DATABASE \"$database\"")
            }
        }
        val url = postgres.jdbcUrl.replaceFirst(Regex("/[^/?]+(\\?|$)"), "/$database$1")
        fun flyway(target: String?) = Flyway.configure()
            .dataSource(url, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .apply { if (target != null) target(target) }
            .load()

        flyway(LAST_1X).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            // Rows as 1.x wrote them; no command writes the old names any more.
            connection.createStatement().use {
                it.execute(
                    """
                    INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                    INSERT INTO catalogs (id, tenant_key, name, type, catalog_metadata) VALUES
                        ('letters', 'acme', 'Letters', 'AUTHORED', '{
                            "keywords": ["letters"],
                            "presentation": {"iconAssetSlug": "logo", "imageAssetSlugs": ["logo", "preview"]},
                            "attributes": [
                                {"catalog": "shared", "key": "audience", "value": "municipalities"},
                                {"catalog": "letters", "key": "tone", "value": "formal"}
                            ]
                        }'),
                        ('plain', 'acme', 'Plain', 'AUTHORED', '{}');
                    """.trimIndent(),
                )
            }
        }
        flyway(null).migrate()

        val metadata = DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { st ->
                st.executeQuery("SELECT catalog_key, catalog_metadata::text FROM catalogs ORDER BY catalog_key").use { rs ->
                    buildMap<String, JsonNode> { while (rs.next()) put(rs.getString(1), mapper.readTree(rs.getString(2))) }
                }
            }
        }

        assertThat(metadata.getValue("letters")).isEqualTo(
            mapper.readTree(
                """
                {
                  "keywords": ["letters"],
                  "presentation": {"iconImageKey": "logo", "imageKeys": ["logo", "preview"]},
                  "attributes": [
                    {"catalogKey": "shared", "key": "audience", "value": "municipalities"},
                    {"catalogKey": "letters", "key": "tone", "value": "formal"}
                  ]
                }
                """,
            ),
        )
        assertThat(metadata.getValue("plain")).isEqualTo(mapper.readTree("{}"))
    }
}
