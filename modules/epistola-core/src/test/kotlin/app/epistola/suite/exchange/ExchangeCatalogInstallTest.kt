// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.assets.queries.ListAssets
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogUpstreamCheckStore
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.releases.ReleaseChannel
import app.epistola.suite.catalog.releases.ReleaseProvenance
import app.epistola.suite.catalog.sources.InstallCatalogRelease
import app.epistola.suite.catalog.sources.ListAvailableCatalogReleases
import app.epistola.suite.catalog.sources.ResourceChangeKind
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
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.exportReleased
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withReleaseFingerprint
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
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

            val installed = GetCatalog(testCatalog(consumer, CatalogKey.of("invoices"))).query()!!
            assertThat(installed.hasSource).isTrue()
            assertThat(installed.latestReleaseVersion).isEqualTo("1.0.0")
            assertThat(installed.latestReleasedAt).isNotNull()
            // The source is the one answer to "where did this come from", for every kind of
            // subscribed catalog. A scheme rather than columns of its own.
            assertThat(installed.sourceUrl).isEqualTo("exchange:acme/invoices")
            // Its release says so too: how it got here, and from where (shown, never decided by).
            val release = app.epistola.suite.catalog.releases.ListCatalogReleases(testCatalog(consumer, CatalogKey.of("invoices")), app.epistola.suite.catalog.releases.CatalogReleaseStatus.RELEASED).query().single()
            assertThat(release.releasedVia).isEqualTo(ReleaseChannel.EXCHANGE)
            assertThat(release.releasedFrom).isEqualTo("exchange:acme/invoices")
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

        val recorded = upstreamChecks.stateFor(testCatalog(consumer, CatalogKey.of("invoices")))!!
        assertThat(recorded.availableVersion).isEqualTo("1.0.0")
        assertThat(recorded.receivedArchiveSha256).isNotNull()
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

            val upgraded = UpgradeExchangeCatalog(testCatalog(consumer, CatalogKey.of("invoices"))).execute()

            assertThat(upgraded.release.version).isEqualTo("2.0.0")
            assertThat(GetCatalog(testCatalog(consumer, CatalogKey.of("invoices"))).query()!!.latestReleaseVersion).isEqualTo("2.0.0")
        }
    }

    @Test
    fun `the source's releases it lacks are listed and installed beside the others, a fix to an older line too`() {
        val consumer = connectedTenant("install-side-by-side")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")
        val invoices = CatalogKey.of("invoices")
        withMediator { InstallExchangeCatalog(consumer, "acme", "invoices").execute() }
        exchange.publish("acme", "invoices", releaseArchive("invoices", "2.0.0"), version = "2.0.0")
        withMediator { InstallCatalogRelease(testCatalog(consumer, invoices), "2.0.0").execute() }
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.1"), version = "1.0.1")

        val available = withMediator { ListAvailableCatalogReleases(testCatalog(consumer, invoices)).query() }

        assertThat(available.available.map { it.releaseVersion to it.comparedTo }).`as`("the fix, against the release below it")
            .containsExactly("1.0.1" to "1.0.0")
        assertThat(available.available.single().changes.map { it.type to it.change }).containsExactly("theme" to ResourceChangeKind.MODIFIED)

        val installed = withMediator { InstallCatalogRelease(testCatalog(consumer, invoices), "1.0.1").execute() }

        assertThat(installed.newlyInstalled).isTrue()
        assertThat(withMediator { ListCatalogReleaseSummaries(testCatalog(consumer, invoices)).query() }.map { it.version }).containsExactlyInAnyOrder("1.0.0", "1.0.1", "2.0.0")
        assertThat(withMediator { GetCatalog(testCatalog(consumer, invoices)).query()!! }.latestReleaseVersion).`as`("what it mirrors stays the highest").isEqualTo("2.0.0")
        assertThat(withMediator { InstallCatalogRelease(testCatalog(consumer, invoices), "1.0.1").execute() }.newlyInstalled).`as`("again, nothing changes").isFalse()
        assertThat(withMediator { ListAvailableCatalogReleases(testCatalog(consumer, invoices)).query() }.available).isEmpty()
        assertThatThrownBy { withMediator { InstallCatalogRelease(testCatalog(consumer, invoices), "9.9.9").execute() } }
            .isInstanceOf(app.epistola.suite.catalog.releases.CatalogReleaseNotFoundException::class.java)
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
    fun `a second catalog with the same key from another namespace is installed beside it, not merged`() {
        val consumer = connectedTenant("install-collision")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")
        exchange.publish("globex", "invoices", releaseArchive("invoices", "9.0.0", namespace = "globex"), version = "9.0.0")

        withMediator {
            InstallExchangeCatalog(consumer, "acme", "invoices").execute()
            // Within a tenant a catalog is named by namespace and key (ADR 0034, section 9).
            InstallExchangeCatalog(consumer, "globex", "invoices").execute()

            val acme = GetCatalog(CatalogId("acme", CatalogKey.of("invoices"), TenantId(consumer))).query()!!
            assertThat(acme.sourceUrl).isEqualTo("exchange:acme/invoices")
            assertThat(acme.latestReleaseVersion).isEqualTo("1.0.0")
            val globex = GetCatalog(CatalogId("globex", CatalogKey.of("invoices"), TenantId(consumer))).query()!!
            assertThat(globex.sourceUrl).isEqualTo("exchange:globex/invoices")
            assertThat(globex.latestReleaseVersion).isEqualTo("9.0.0")
        }
    }

    @Test
    fun `a catalog made here under the same name is not overwritten either`() {
        val consumer = connectedTenant("install-authored-clash")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")

        withMediator {
            // Its own invoices at the address the Exchange names it by: acme/invoices.
            runCatching { TestNamespaces.grant(consumer, "acme").execute() }
            CreateCatalog(consumer, CatalogKey.of("invoices"), "My own invoices", namespace = "acme").execute()

            assertThatThrownBy {
                InstallExchangeCatalog(consumer, "acme", "invoices").execute()
            }.isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("a catalog made here or imported from a file")
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
     * Every catalog seeded on Epistola Exchange names its images the way a person would --
     * `municipality-mark` -- and until the `/images` surface existed the importer parsed that slug
     * as a UUID and refused the whole install. This is the archive shape that used to be the
     * blocker, installing.
     */
    @Test
    fun `an image named by a readable slug installs`() {
        val consumer = connectedTenant("install-image-slug")
        exchange.publish("acme", "broken", readableImageSlugArchive(), version = "1.0.0")

        withMediator {
            val result = InstallExchangeCatalog(consumer, "acme", "broken").execute()

            assertThat(result.aborted).isFalse()
            val image = ListAssets(consumer, catalog = testCatalog(consumer, CatalogKey.of("broken"))).query().single()
            assertThat(image.id.value).isEqualTo("municipality-mark")
            assertThat(image.mediaType.mimeType).isEqualTo("image/svg+xml")
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
        // An archive whose image declares a content hash its bytes do not match, so validation
        // refuses it and the whole install aborts.
        exchange.publish("acme", "broken", brokenArchive(), version = "1.0.0")

        withMediator {
            val result = InstallExchangeCatalog(consumer, "acme", "broken").execute()

            assertThat(result.aborted).isTrue()
            assertThat(result.rolledBack).isTrue()
            assertThat(GetCatalog(testCatalog(consumer, CatalogKey.of("broken"))).query()).isNull()
        }

        // Relocation mints a resource identity from a database trigger on every resource insert,
        // so a rollback that only removed the catalog would leave the registry describing
        // resources that no longer exist. DeleteCatalog cascades them; this is what says so.
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
        exchange.publish("acme", "broken", brokenArchive(), version = "1.0.0")

        withMediator {
            assertThat(InstallExchangeCatalog(consumer, "acme", "broken").execute().aborted).isTrue()

            // The publisher fixes it. Nothing from the failed attempt stands in the way.
            exchange.hostedCatalogs.clear()
            exchange.publish("acme", "broken", releaseArchive("broken", "1.1.0"), version = "1.1.0")

            val retry = InstallExchangeCatalog(consumer, "acme", "broken").execute()

            assertThat(retry.aborted).isFalse()
            assertThat(GetCatalog(testCatalog(consumer, CatalogKey.of("broken"))).query()!!.latestReleaseVersion).isEqualTo("1.1.0")
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

            assertThat(GetCatalog(testCatalog(consumer, CatalogKey.of("invoices"))).query()).isNull()
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
     * Release [version] of catalog [slug] in exchange namespace [namespace], as its publisher exports
     * it — the same bytes a publisher submits.
     *
     * Built in a throwaway tenant of its own so the publisher's copy can never be mistaken for the
     * consumer's, and so a second call for a later version starts from a clean catalog.
     */
    private fun releaseArchive(slug: String, version: String, namespace: String = "acme"): ByteArray {
        val publisher = createTenant("publisher-$slug-$version").id
        lastPublisher = publisher
        val catalogKey = CatalogKey.of(slug)
        return withMediator {
            TestNamespaces.grant(publisher, namespace).execute()
            CreateCatalog(publisher, catalogKey, "Invoices", namespace = namespace).execute()
            CreateTheme(
                id = ThemeId(ThemeKey.of("thm"), testCatalog(TenantId(publisher), catalogKey)),
                name = "Theme $version",
            ).execute()
            MarkCatalogReady(testCatalog(publisher, catalogKey)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(publisher, catalogKey), version = version).execute()
            mediator.exportReleased(testCatalog(app.epistola.suite.common.ids.TenantId(publisher), catalogKey)).zipBytes
        }
    }

    /** The resource identities relocation's registry holds for one catalog. */
    private fun resourceIdentities(tenant: TenantKey, catalogKey: String): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery(
            "SELECT resource_id::text FROM catalog_resources WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}",
        ).bind("t", tenant).bindCatalog("c", testCatalog(tenant, catalogKey)).mapTo(String::class.java).list()
    }

    /**
     * How many resource identities relocation's registry holds for one catalog.
     *
     * Scoped to the catalog rather than the tenant: every tenant is seeded with the system
     * catalog, whose identities are none of this test's business.
     */
    private fun registeredResourceIdentities(tenant: TenantKey, catalogKey: String): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery(
            "SELECT COUNT(*) FROM catalog_resources WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}",
        ).bind("t", tenant).bindCatalog("c", testCatalog(tenant, catalogKey)).mapTo(Int::class.java).one()
    }

    /**
     * A minimal, *valid* archive whose one image cannot be imported: its media type is not a
     * seeded `asset_types` row, so the insert fails a foreign key.
     *
     * The archive has to pass the validator and fail *inside* the install loop — one the validator
     * rejects never creates a catalog, and so would not exercise the abort path at all.
     */
    private fun brokenArchive(): ByteArray = readableImageSlugArchive(mediaType = "image/x-unknown")

    /**
     * An SVG that would make the renderer fetch a URL is refused on every write path, and catalog
     * install is one: the image fails, and with it the install.
     */
    @Test
    fun `an archive whose SVG references an external resource does not install it`() {
        val consumer = connectedTenant("install-unsafe-svg")
        exchange.publish("acme", "broken", readableImageSlugArchive(svg = UNSAFE_SVG), version = "1.0.0")

        withMediator {
            val result = InstallExchangeCatalog(consumer, "acme", "broken").execute()

            assertThat(result.aborted).isTrue()
            assertThat(GetCatalog(testCatalog(consumer, CatalogKey.of("broken"))).query()).isNull()
        }
    }

    @Test
    fun `a catalog ZIP whose SVG references an external resource does not install it`() {
        val tenant = connectedTenant("zip-unsafe-svg")

        // Installing a release is all or nothing: the image is refused, and so is the release, and the
        // catalog the import would have created is not left behind.
        withMediator {
            val result = ImportCatalogZip(tenantKey = tenant, zipBytes = readableImageSlugArchive(svg = UNSAFE_SVG), provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()
            assertThat(result.aborted).isTrue()
            assertThat(result.results.single().errorMessage).contains("SVG not accepted")
            assertThat(ListAssets(tenant, catalog = testCatalog(tenant, CatalogKey.of("broken"))).query()).isEmpty()
            assertThat(GetCatalog(testCatalog(tenant, CatalogKey.of("broken"))).query()).isNull()
        }
    }

    private fun readableImageSlugArchive(
        mediaType: String = "image/svg+xml",
        svg: String = """<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96"></svg>""",
    ): ByteArray {
        val svgContentHash = MessageDigest.getInstance("SHA-256").digest(svg.toByteArray()).joinToString("") { "%02x".format(it) }
        val manifest = """
            {"schemaVersion":8,
             "catalog":{"registry":"exchange.epistola.app","namespace":"acme","key":"broken","name":"Broken","description":null,"attributes":[],"keywords":[],"presentation":null,"license":null},
             "publisher":{"name":"Test","url":null},
             "release":{"version":"1.0.0","releasedAt":null,"fingerprint":null},
             "compatibility":null,"includes":null,"dependencies":[],
             "resources":[{"type":"image","key":"municipality-mark","name":"Municipality mark",
                           "description":null,"updatedAt":null,
                           "detailUrl":"./resources/image/municipality-mark.json"}]}
        """.trimIndent()
        val detail = """
            {"schemaVersion":8,"resource":{"key":"municipality-mark","name":"Municipality mark",
             "mediaType":"$mediaType","width":96,"height":96,
             "contentHash":"$svgContentHash","type":"image"}}
        """.trimIndent()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            mapOf(
                "catalog.json" to manifest,
                "resources/image/municipality-mark.json" to detail,
                "bin/$svgContentHash" to svg,
            ).forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        // A wire-v8 release names its fingerprint, as its publisher wrote it.
        return withReleaseFingerprint(out.toByteArray())
    }

    private companion object {
        const val UNSAFE_SVG = """<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96"><image href="http://169.254.169.254/latest/meta-data" width="96" height="96"/></svg>"""
    }
}
