// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The guards scan the whole repository while the rest of the build runs: other tasks compile into
 * `build/` and delete from it at the same time. A walk that so much as lists a build directory can
 * then fail on a file that is gone by the time it is read (`NoSuchFileException`, as
 * DesignSystemClassTest did in a full `./gradlew test`). So the walk never enters one.
 */
class RepoSourcesTest {
    @Test
    fun `the walk lists sources and never enters build output or installed dependencies`(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("app/src/main/resources/templates")).resolve("page.html")
        Files.writeString(source, "<p></p>")
        // A build directory that cannot be read stands in for one another task changes under the walk.
        val busy = Files.createDirectories(root.resolve("app/build/kotlin/caches"))
        Files.writeString(busy.resolve("gone.tab"), "")
        busy.toFile().setReadable(false)
        Files.createDirectories(root.resolve("app/node_modules/pkg")).resolve("index.js").let { Files.writeString(it, "") }
        try {
            assertThat(RepoSources.filesUnder(root)).containsExactly(source)
        } finally {
            busy.toFile().setReadable(true)
        }
    }
}
