// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogArchiveBuilder
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.sources.ManifestUrlReleaseSource
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

/**
 * Builds a bundled catalog's release archive once per JVM, not once per tenant.
 *
 * Every tenant a test creates installs the system catalog, through the one install path production
 * uses: the release's archive, written as a ZIP and imported. Writing it deflates the system
 * catalog's 14 MB of font faces, which made each new tenant cost three times as much. A tenant is
 * created rarely in production and in nearly every test, so the archive of a `classpath:` source
 * (which cannot change while the JVM runs) is built once here and its bytes reused; importing them
 * is unchanged. Any other source is fetched and built every time.
 */
@AutoConfiguration
class BundledCatalogArchiveCacheConfiguration {
    companion object {
        @Bean
        @JvmStatic
        fun bundledCatalogArchiveCache(): BeanPostProcessor = BundledCatalogArchiveCachePostProcessor()
    }
}

/** Puts [CachingManifestUrlReleaseSource] in place of the context's [ManifestUrlReleaseSource]. */
class BundledCatalogArchiveCachePostProcessor :
    BeanPostProcessor,
    BeanFactoryAware {
    private lateinit var beans: BeanFactory

    override fun setBeanFactory(beanFactory: BeanFactory) {
        beans = beanFactory
    }

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any = if (bean.javaClass == ManifestUrlReleaseSource::class.java) {
        CachingManifestUrlReleaseSource(beans.getBean(CatalogClient::class.java), beans.getBean(CatalogArchiveBuilder::class.java))
    } else {
        bean
    }
}

/** A [ManifestUrlReleaseSource] that keeps the archives of `classpath:` sources it built. */
class CachingManifestUrlReleaseSource(
    client: CatalogClient,
    archiveBuilder: CatalogArchiveBuilder,
) : ManifestUrlReleaseSource(client, archiveBuilder) {
    override fun archive(catalog: Catalog, version: String): ByteArray {
        val url = catalog.sourceUrl
        if (url == null || !url.startsWith("classpath:")) return super.archive(catalog, version)
        val key = url to version
        synchronized(built) { built[key] }?.let { return it }
        return super.archive(catalog, version).also { bytes -> synchronized(built) { built[key] = bytes } }
    }

    private companion object {
        /** The bundled catalogs a test JVM installs: the system, demo and fixture catalogs, each a few releases at most. */
        const val MAX_ARCHIVES = 8

        /** Shared by every Spring context in the JVM; least recently used out first. */
        val built = object : LinkedHashMap<Pair<String, String>, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, ByteArray>): Boolean = size > MAX_ARCHIVES
        }
    }
}
