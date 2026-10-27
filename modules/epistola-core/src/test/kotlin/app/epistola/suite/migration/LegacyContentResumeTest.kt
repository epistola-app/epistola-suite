// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/**
 * The 2.0 upgrade's legacy blobs (step 5, `V20261101000500`) interrupted halfway resume: it moves the 1.x content
 * store's blobs a batch per transaction, outside Flyway's, so a failure keeps the batches that
 * committed, records no failed migration, and the next migrate moves the rest, each blob once.
 *
 * The failure is a trigger the test puts on `asset_content`, refusing one blob of the second batch
 * the first time; the second run has it removed, as an operator fixes what failed. On every
 * supported PostgreSQL major version, like [DataPreservationMigrationIT].
 *
 * Seeded with raw SQL on purpose: `content_store` is what no command writes any more.
 */
@Tag("integration")
class LegacyContentResumeTest {

    @ParameterizedTest(name = "on PostgreSQL {0}")
    @ValueSource(ints = [17, 18])
    fun `an interrupted move of the legacy blobs resumes where it stopped, moving each once`(postgresVersion: Int) {
        if (postgresVersion == TestRuntimeLifecycle.postgresMajorVersion) {
            run(TestRuntimeLifecycle.postgres())
        } else {
            TestRuntimeLifecycle.newPostgresContainer(postgresVersion).use { postgres ->
                postgres.start()
                run(postgres)
            }
        }
    }

    private fun run(postgres: PostgreSQLContainer) {
        val database = "legacy_content_resume"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { admin ->
            admin.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS \"$database\"")
                it.execute("CREATE DATABASE \"$database\"")
            }
        }
        val url = postgres.jdbcUrl.replaceFirst(Regex("/[^/?]+(\\?|$)"), "/$database$1")
        fun flyway(target: String) = Flyway.configure().dataSource(url, postgres.username, postgres.password).locations("classpath:db/migration").target(target).load()
        fun <T> db(block: (Connection) -> T): T = DriverManager.getConnection(url, postgres.username, postgres.password).use(block)

        flyway(LAST_1X).migrate()
        db { connection ->
            connection.exec(
                """
                INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                INSERT INTO catalogs (id, tenant_key, name, type) VALUES ('letters', 'acme', 'Letters', 'AUTHORED');
                -- 150 images the 1.x backfill never reached, more than one batch (100), each with bytes of its own.
                INSERT INTO assets (id, tenant_key, catalog_key, name, media_type, size_bytes, sensitive)
                SELECT 'a' || lpad(n::text, 3, '0'), 'acme', 'letters', 'image ' || n, 'image/png', 4, false FROM generate_series(1, $ASSETS) AS n;
                INSERT INTO content_store (key, content, content_type, size_bytes)
                SELECT 'assets/acme/a' || lpad(n::text, 3, '0'), decode(lpad(to_hex(n), 8, '0'), 'hex'), 'image/png', 4 FROM generate_series(1, $ASSETS) AS n;
                -- A failure in the second batch: the 120th blob is refused while this trigger is there.
                CREATE FUNCTION test_refuse_one_blob() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN
                    IF NEW.content = decode(lpad(to_hex($FAILING), 8, '0'), 'hex') THEN RAISE EXCEPTION 'the test refuses blob $FAILING'; END IF;
                    RETURN NEW;
                END ${'$'}${'$'};
                CREATE TRIGGER test_refuse_one_blob BEFORE INSERT ON asset_content FOR EACH ROW EXECUTE FUNCTION test_refuse_one_blob();
                """.trimIndent(),
            )
        }

        assertThatThrownBy { flyway(LEGACY_CONTENT).migrate() }.hasStackTraceContaining("the test refuses blob $FAILING")
        db { connection ->
            assertThat(connection.count("SELECT count(*) FROM assets WHERE content_hash IS NOT NULL")).`as`("the first batch committed").isEqualTo(100)
            assertThat(connection.count("SELECT count(*) FROM flyway_schema_history WHERE version = '$LEGACY_CONTENT'")).`as`("and no failed migration is recorded").isZero()
            connection.exec("DROP TRIGGER test_refuse_one_blob ON asset_content; DROP FUNCTION test_refuse_one_blob();")
        }

        flyway(LEGACY_CONTENT).migrate()

        db { connection ->
            assertThat(connection.count("SELECT count(*) FROM assets WHERE content_hash IS NULL")).`as`("every asset has its hash").isZero()
            assertThat(connection.count("SELECT count(*) FROM asset_content")).`as`("each blob moved once").isEqualTo(ASSETS)
            assertThat(connection.count("SELECT count(DISTINCT content_hash) FROM assets")).isEqualTo(ASSETS)
            assertThat(connection.count("SELECT count(*) FROM flyway_schema_history WHERE version = '$LEGACY_CONTENT' AND success")).isOne()
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.count(sql: String): Int = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            rs.next()
            rs.getInt(1)
        }
    }

    private companion object {
        const val LEGACY_CONTENT = "20261101000500"
        const val ASSETS = 150
        const val FAILING = 120
    }
}
