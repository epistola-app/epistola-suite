// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.CatalogUpstreamCheckStore
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.FakeExchangeServer
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Installing a catalog published on Epistola Exchange, end to end over a real socket.
 *
 * The archives are genuine: exported from a catalog authored in another tenant, which is exactly
 * what a publisher sends Exchange. Anything less would not go through the validator, the schema
 * gate, or the fingerprint check that the import actually performs.
 */
class ExchangeCatalogInstallTest : ExchangeIntegrationTestBase() {

    @Autowired
    private lateinit var upstreamChecks: CatalogUpstreamCheckStore

    @Autowired
    private lateinit var jdbi: Jdbi

    /** The throwaway tenant the most recent [releaseArchive] published from. */
    private var lastPublisher: TenantKey? = null

    @Test
    fun `a release becomes a subscribed catalog that remembers where it came from`() {
        val consumer = connectedTenant("install-basic")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")

        withMediator {
            val result = InstallExchangeCatalog(consumer, "acme", "invoices").execute()

            assertThat(result.aborted).isFalse()
            assertThat(result.release.version).isEqualTo("1.0.0")

            val installed = GetCatalog(consumer, CatalogKey.of("invoices")).query()!!
            assertThat(installed.type).isEqualTo(CatalogType.SUBSCRIBED)
            assertThat(installed.installedReleaseVersion).isEqualTo("1.0.0")
            assertThat(installed.installedAt).isNotNull()
            // The source is the one answer to "where did this come from", for every kind of
            // subscribed catalog. A scheme rather than columns of its own.
            assertThat(installed.sourceUrl).isEqualTo("exchange:acme/invoices")
        }
    }

    /**
     * Relocation's `resource_id` is documented as internal — "never serialized into public URLs or
     * catalog exchange data". This is that promise, checked from the consumer's side: a catalog
     * installed from Exchange gets identities minted by its own trigger, not the publisher's,
     * because the wire format never carried them. If it ever did, two installations would share an
     * identity that each believes it owns.
     */
    @Test
    fun `an installed catalog gets its own resource identities, not the publisher's`() {
        val consumer = connectedTenant("install-identities")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")
        val publisher = requireNotNull(lastPublisher)

        withMediator { InstallExchangeCatalog(consumer, "acme", "invoices").execute() }

        val published = resourceIdentities(publisher, "invoices")
        val installed = resourceIdentities(consumer, "invoices")
        assertThat(published).isNotEmpty()
        assertThat(installed).hasSameSizeAs(published)
        assertThat(installed).doesNotContainAnyElementsOf(published)
    }

    @Test
    fun `installing records the release so the catalogs page needs no call of its own`() {
        val consumer = connectedTenant("install-records")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")

        withMediator {
            InstallExchangeCatalog(consumer, "acme", "invoices").execute()
        }

        val recorded = upstreamChecks.stateFor(consumer, CatalogKey.of("invoices"))!!
        assertThat(recorded.availableVersion).isEqualTo("1.0.0")
        assertThat(recorded.installedArchiveSha256).isNotNull()
        assertThat(recorded.failure).isNull()
        assertThat(recorded.upgradeAvailable("1.0.0")).isFalse()
    }

    @Test
    fun `upgrading follows the source the catalog already records`() {
        val consumer = connectedTenant("install-upgrade")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")

        withMediator {
            InstallExchangeCatalog(consumer, "acme", "invoices").execute()
            exchange.publish("acme", "invoices", releaseArchive("invoices", "2.0.0"), version = "2.0.0")

            val upgraded = UpgradeExchangeCatalog(consumer, CatalogKey.of("invoices")).execute()

            assertThat(upgraded.release.version).isEqualTo("2.0.0")
            assertThat(GetCatalog(consumer, CatalogKey.of("invoices")).query()!!.installedReleaseVersion).isEqualTo("2.0.0")
        }
    }

    /**
     * The regression that matters most.
     *
     * A catalog is addressed within a tenant by the slug in its own manifest, so two namespaces
     * publishing the same key both want the same local catalog. Both are SUBSCRIBED, so the
     * existing type-flip guard does not fire — without a check of its own the second install
     * silently overwrites the first, and the tenant ends up with one catalog claiming to be two.
     */
    @Test
    fun `a second catalog with the same key from another namespace is refused, not merged`() {
        val consumer = connectedTenant("install-collision")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")
        exchange.publish("globex", "invoices", releaseArchive("invoices", "9.0.0"), version = "9.0.0")

        withMediator {
            InstallExchangeCatalog(consumer, "acme", "invoices").execute()

            assertThatThrownBy {
                InstallExchangeCatalog(consumer, "globex", "invoices").execute()
            }.isInstanceOf(ValidationException::class.java)
                .satisfies({ assertThat((it as ValidationException).code).isEqualTo(ValidationCode.EXCHANGE_CATALOG_KEY_TAKEN) })
                .hasMessageContaining("acme/invoices")

            // The first install is untouched, which is the point.
            val installed = GetCatalog(consumer, CatalogKey.of("invoices")).query()!!
            assertThat(installed.sourceUrl).isEqualTo("exchange:acme/invoices")
            assertThat(installed.installedReleaseVersion).isEqualTo("1.0.0")
        }
    }

    @Test
    fun `an authored catalog of the same name is not overwritten either`() {
        val consumer = connectedTenant("install-authored-clash")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")

        withMediator {
            CreateCatalog(consumer, CatalogKey.of("invoices"), "My own invoices").execute()

            assertThatThrownBy {
                InstallExchangeCatalog(consumer, "acme", "invoices").execute()
            }.isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("an authored catalog")
        }
    }

    @Test
    fun `a withdrawn release is never installed, even though Exchange still returns it`() {
        val consumer = connectedTenant("install-withdrawn")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "2.0.0"), version = "2.0.0", availability = "WITHDRAWN")

        withMediator {
            assertThatThrownBy {
                InstallExchangeCatalog(consumer, "acme", "invoices", version = "2.0.0").execute()
            }.isInstanceOf(ValidationException::class.java)
                .satisfies({ assertThat((it as ValidationException).code).isEqualTo(ValidationCode.EXCHANGE_RELEASE_UNAVAILABLE) })

            // Asking for no particular version takes the newest that is actually offered.
            val installed = InstallExchangeCatalog(consumer, "acme", "invoices").execute()
            assertThat(installed.release.version).isEqualTo("1.0.0")
        }
    }

    /**
     * The import creates the catalog row before it installs anything into it, and its abort path
     * does not undo that. Left alone, a first install that failed would leave an empty catalog
     * occupying the ID — claiming nothing had changed while blocking the retry that would fix it.
     */
    @Test
    fun `a first install that aborts leaves no catalog behind`() {
        val consumer = connectedTenant("install-abort-clean")
        // An archive whose asset the importer cannot accept: asset ids are UUIDs in Suite, so a
        // slug-named one fails to import and aborts the whole install.
        exchange.publish("acme", "broken", brokenAssetArchive(), version = "1.0.0")

        withMediator {
            val result = InstallExchangeCatalog(consumer, "acme", "broken").execute()

            assertThat(result.aborted).isTrue()
            assertThat(result.rolledBack).isTrue()
            assertThat(GetCatalog(consumer, CatalogKey.of("broken")).query()).isNull()
        }

        // Relocation mints a resource identity from a database trigger on every resource insert,
        // so a rollback that only removed the catalog would leave the registry describing
        // resources that no longer exist. UnregisterCatalog cascades them; this is what says so.
        assertThat(registeredResourceIdentities(consumer, "broken")).isZero()
    }

    /**
     * Retrying already worked at this level — the conflict check lets the same coordinates through —
     * so this guards the end state rather than the rollback. What the stale row actually blocked
     * was the *dialog*, which is covered in `ExchangeCatalogHandlerTest`.
     */
    @Test
    fun `an aborted install can simply be retried once the release is fixed`() {
        val consumer = connectedTenant("install-abort-retry")
        exchange.publish("acme", "broken", brokenAssetArchive(), version = "1.0.0")

        withMediator {
            assertThat(InstallExchangeCatalog(consumer, "acme", "broken").execute().aborted).isTrue()

            // The publisher fixes it. Nothing from the failed attempt stands in the way.
            exchange.hostedCatalogs.clear()
            exchange.publish("acme", "broken", releaseArchive("broken", "1.1.0"), version = "1.1.0")

            val retry = InstallExchangeCatalog(consumer, "acme", "broken").execute()

            assertThat(retry.aborted).isFalse()
            assertThat(GetCatalog(consumer, CatalogKey.of("broken")).query()!!.installedReleaseVersion).isEqualTo("1.1.0")
        }
    }

    @Test
    fun `an archive that does not match its published digest is refused`() {
        val consumer = connectedTenant("install-digest")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")
        // Same length is irrelevant; what matters is that the bytes are not the ones announced.
        exchange.archiveResponse = { FakeExchangeServer.Response(200, "not the catalog you were promised") }

        withMediator {
            assertThatThrownBy {
                InstallExchangeCatalog(consumer, "acme", "invoices").execute()
            }.isInstanceOf(ValidationException::class.java)
                .satisfies({ assertThat((it as ValidationException).code).isEqualTo(ValidationCode.EXCHANGE_ARCHIVE_REJECTED) })

            assertThat(GetCatalog(consumer, CatalogKey.of("invoices")).query()).isNull()
        }
    }

    @Test
    fun `installing is refused when the tenant has the feature switched off`() {
        val consumer = createTenant("install-feature-off").id
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")

        withMediator {
            assertThatThrownBy {
                InstallExchangeCatalog(consumer, "acme", "invoices").execute()
            }.isInstanceOf(ValidationException::class.java)
                .satisfies({ assertThat((it as ValidationException).code).isEqualTo(ValidationCode.EXCHANGE_INSTALL_UNAVAILABLE) })
        }
    }

    @Test
    fun `installing is refused when the tenant is not connected`() {
        val consumer = createTenant("install-not-connected").id
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")

        withMediator {
            SaveFeatureToggle(consumer, KnownFeatures.CATALOG_INSTALLING, true).execute()

            assertThatThrownBy {
                InstallExchangeCatalog(consumer, "acme", "invoices").execute()
            }.isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("not connected")
        }
    }

    /** A tenant with the feature on and an active Exchange connection. */
    private fun connectedTenant(name: String): TenantKey {
        val tenant = createTenant(name).id
        withMediator {
            SaveFeatureToggle(tenant, KnownFeatures.CATALOG_INSTALLING, true).execute()
            SaveFeatureToggle(tenant, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            StartExchangeConnection(tenant, "https://suite.example/oauth/exchange/callback").execute()
            CompleteExchangeConnection(
                tenant,
                requireNotNull(exchange.latestState.get()),
                "authorization-code",
                FakeExchangeServer.OAUTH_APPLICATION_ID,
                exchange.baseUrl,
            ).execute()
        }
        return tenant
    }

    /**
     * A real exported catalog archive, released at [version] — the same bytes a publisher submits.
     *
     * Built in a throwaway tenant of its own so the publisher's copy can never be mistaken for the
     * consumer's, and so a second call for a later version starts from a clean catalog.
     */
    private fun releaseArchive(slug: String, version: String): ByteArray {
        val publisher = createTenant("publisher-$slug-$version").id
        lastPublisher = publisher
        val catalogKey = CatalogKey.of(slug)
        return withMediator {
            CreateCatalog(publisher, catalogKey, "Invoices").execute()
            CreateTheme(
                id = ThemeId(ThemeKey.of("thm"), CatalogId(catalogKey, TenantId(publisher))),
                name = "Theme $version",
            ).execute()
            ReleaseCatalogVersion(tenantKey = publisher, catalogKey = catalogKey, version = version).execute()
            ExportCatalogZip(tenantKey = publisher, catalogKey = catalogKey).execute().zipBytes
        }
    }

    /** The resource identities relocation's registry holds for one catalog. */
    private fun resourceIdentities(tenant: TenantKey, catalogKey: String): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery(
            "SELECT resource_id::text FROM catalog_resources WHERE tenant_key = :t AND catalog_key = :c",
        ).bind("t", tenant).bind("c", catalogKey).mapTo(String::class.java).list()
    }

    /**
     * How many resource identities relocation's registry holds for one catalog.
     *
     * Scoped to the catalog rather than the tenant: every tenant is seeded with the system
     * catalog, whose identities are none of this test's business.
     */
    private fun registeredResourceIdentities(tenant: TenantKey, catalogKey: String): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery(
            "SELECT COUNT(*) FROM catalog_resources WHERE tenant_key = :t AND catalog_key = :c",
        ).bind("t", tenant).bind("c", catalogKey).mapTo(Int::class.java).one()
    }

    /**
     * A minimal, *valid* archive whose one asset cannot be imported.
     *
     * Suite addresses assets by UUID, so a slug-named one fails at import — which is the shape
     * every catalog currently seeded on Epistola Exchange happens to have. Modelled on a real
     * Exchange archive, including its null `release.fingerprint`, because the point is to reach the
     * abort path: an archive the *validator* rejects never gets far enough to leave a catalog
     * behind, and would not test this at all.
     */
    private fun brokenAssetArchive(): ByteArray {
        val manifest = """
            {"schemaVersion":6,
             "catalog":{"slug":"broken","name":"Broken","description":null,"attributes":[],"keywords":[],"presentation":null,"license":null},
             "publisher":{"name":"Test","url":null},
             "release":{"version":"1.0.0","releasedAt":null,"fingerprint":null},
             "compatibility":null,"includes":null,"dependencies":[],
             "resources":[{"type":"asset","slug":"municipality-mark","name":"Municipality mark",
                           "description":null,"updatedAt":null,
                           "detailUrl":"./resources/asset/municipality-mark.json","compatibility":null}]}
        """.trimIndent()
        val detail = """
            {"schemaVersion":6,"resource":{"slug":"municipality-mark","name":"Municipality mark",
             "mediaType":"image/svg+xml","width":96,"height":96,
             "contentUrl":"./resources/asset/municipality-mark.svg","type":"asset"}}
        """.trimIndent()
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96"></svg>"""

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            mapOf(
                "catalog.json" to manifest,
                "resources/asset/municipality-mark.json" to detail,
                "resources/asset/municipality-mark.svg" to svg,
            ).forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
