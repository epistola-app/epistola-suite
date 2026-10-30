// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.storage

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * Only PostgreSQL is tested as the document content store. S3 and filesystem were never exercised
 * by CI or in production, so the suite refuses to start on them rather than run on something
 * untested.
 */
class StorageBackendSupportTest {

    private val configuration = StorageConfiguration()

    @Test
    fun `S3 and filesystem are refused, naming the backend to use`() {
        for (backend in listOf(StorageBackend.S3, StorageBackend.FILESYSTEM)) {
            assertThatThrownBy { store(backend) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("epistola.storage.backend=${backend.name.lowercase()} is not supported")
                .hasMessageContaining("epistola.storage.backend=postgres")
        }
    }

    @Test
    fun `postgres is the store it starts on`() {
        assertThat(store(StorageBackend.POSTGRES)).isNotNull()
    }

    private fun store(backend: StorageBackend) = configuration.documentContentStore(
        StorageProperties(backend = backend, s3 = S3Properties(bucket = "configured")),
        mock(Jdbi::class.java),
        SimpleMeterRegistry(),
    )
}
