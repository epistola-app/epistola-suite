package app.epistola.suite.handlers

import app.epistola.suite.changelog.ChangelogAudience
import app.epistola.suite.changelog.ChangelogService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class ChangelogRendererTest {

    private val renderer = ChangelogRenderer()
    private val service = ChangelogService()

    // language=markdown
    private val taggedMarkdown =
        """
        # Changelog

        ## [Unreleased]

        ### Added

        - **[user]** **Upcoming user feature.** A preview of what's next.
        - **[dev]** **Upcoming plumbing.** Internal prep.

        ## [1.2.0] - 2026-06-01

        ### Added

        - **[user]** **User feature.** Visible to users.
        - **[dev]** **Dev refactor.** Internal only.
        - **Everyone item.** No tag, shown to all.

        ### Fixed

        - **[dev]** **Dev-only fix.** Plumbing.

        ## [1.1.0] - 2026-05-01

        ### Changed

        - **[dev]** **Dev-only release note.** Nothing user-facing here.
        """.trimIndent()

    @Test
    fun `entries parses changelog into structured entries`() {
        val entries = renderer.entries()
        assertThat(entries).isNotEmpty()
        entries.forEach { entry ->
            assertThat(entry.version).matches("\\d+\\.\\d+\\.\\d+")
            assertThat(entry.date).matches("\\d{4}-\\d{2}-\\d{2}")
            assertThat(entry.html).isNotBlank()
        }
    }

    @Test
    fun `entries excludes Unreleased section`() {
        val entries = renderer.entries()
        assertThat(entries.map { it.version }).doesNotContain("Unreleased")
    }

    @Test
    fun `entries are ordered newest first`() {
        val entries = renderer.entries()
        assertThat(entries.size).isGreaterThanOrEqualTo(2)
        val versions = entries.map { it.version }
        assertThat(compareVersions(versions[0], versions[1])).isGreaterThan(0)
    }

    @Test
    fun `entriesSince returns only newer versions`() {
        val allEntries = renderer.entries()
        assertThat(allEntries.size).isGreaterThanOrEqualTo(2)

        val olderVersion = allEntries.last().version
        val since = service.entriesSince(allEntries, olderVersion)

        assertThat(since).hasSizeLessThan(allEntries.size)
        assertThat(since.map { it.version }).doesNotContain(olderVersion)
    }

    @Test
    fun `entriesSince with null returns all entries`() {
        val entries = renderer.entries()
        assertThat(service.entriesSince(entries, null)).isEqualTo(entries)
    }

    @Test
    fun `entriesSince with blank returns all entries`() {
        val entries = renderer.entries()
        assertThat(service.entriesSince(entries, "")).isEqualTo(entries)
    }

    @Test
    fun `entriesSince with latest version returns empty`() {
        val entries = renderer.entries()
        val latest = entries.first().version
        assertThat(service.entriesSince(entries, latest)).isEmpty()
    }

    @Test
    fun `hasUnseenEntries in dev mode uses latest changelog version`() {
        val entries = renderer.entries()
        // Not acknowledged yet — should show
        assertThat(service.hasUnseenEntries(entries, "dev", null)).isTrue()
        // Acknowledged latest — should not show
        val latest = entries.first().version
        assertThat(service.hasUnseenEntries(entries, "dev", latest)).isFalse()
    }

    @Test
    fun `hasUnseenEntries returns false for empty entries`() {
        assertThat(service.hasUnseenEntries(emptyList(), "dev", null)).isFalse()
    }

    @Test
    fun `hasUnseenEntries returns false when already acknowledged`() {
        assertThat(service.hasUnseenEntries(renderer.entries(), "0.12.0", "0.12.0")).isFalse()
    }

    @Test
    fun `hasUnseenEntries handles version suffixes`() {
        val entries = renderer.entries()
        val latest = entries.first().version
        assertThat(service.hasUnseenEntries(entries, "$latest-SNAPSHOT", latest)).isFalse()
    }

    @Test
    fun `hasUnseenEntries returns true when not acknowledged`() {
        val entries = renderer.entries()
        val latest = entries.first().version
        val older = entries.last().version
        assertThat(service.hasUnseenEntries(entries, latest, older)).isTrue()
    }

    @Test
    fun `entries have non-empty summaries`() {
        val entries = renderer.entries()
        entries.forEach { entry ->
            assertThat(entry.summary).isNotBlank()
        }
    }

    @Test
    fun `summary contains category counts`() {
        val entries = renderer.entries()
        val first = entries.first()
        // Every version should have at least one category
        assertThat(first.summary).containsPattern("\\d+ (new feature|fix|change)")
    }

    @Test
    fun `aggregateSummary for single entry returns its summary`() {
        val entries = renderer.entries()
        assertThat(service.aggregateSummary(listOf(entries.first()))).isEqualTo(entries.first().summary)
    }

    @Test
    fun `aggregateSummary for multiple entries aggregates`() {
        val entries = renderer.entries().take(3)
        val summary = service.aggregateSummary(entries)
        assertThat(summary).contains("across 3 versions")
    }

    // language=markdown
    private val commitMarkdown =
        """
        # Changelog

        ## [Unreleased]

        - **[user]** feat(editor): **List nesting.** Tab now nests a sub-list.
        - **[dev]** fix(cluster): **Heartbeat fix.** No longer floods the log.
        - feat(generation): **Bullet glyphs.** circle/square render in PDF.
        - chore(build): **Bump the Kotlin daemon heap.**

        ## [1.0.0] - 2026-01-01

        ### Added

        - **[user]** **Legacy feature.** Old Keep-a-Changelog style.

        ### Fixed

        - **Legacy fix.** Old style fix.
        """.trimIndent()

    @Test
    fun `commit-style entries parse type and scope and strip the prefix`() {
        val html = renderer.entriesFrom(commitMarkdown, ChangelogAudience.ALL, includeUnreleased = true).first { it.version == "Unreleased" }.html
        assertThat(html).contains("List nesting.", "Heartbeat fix.", "Bullet glyphs.", "Bump the Kotlin daemon heap.")
        // The type(scope): prefix must not survive into the output...
        assertThat(html).doesNotContain("feat(editor)").doesNotContain("fix(cluster)").doesNotContain("chore(build)")
        // ...and grouping headers are the friendly type names.
        assertThat(html).contains("<h3>Features</h3>").contains("<h3>Fixes</h3>").contains("<h3>Chores</h3>")
        // Audience + scope chips are present.
        assertThat(html).contains("changelog-badge--user").contains("changelog-badge--dev").contains("changelog-badge--scope")
        assertThat(html).contains(">editor<").contains(">cluster<")
    }

    @Test
    fun `type filter keeps only the matching commit type`() {
        val html = renderer.entriesFrom(commitMarkdown, ChangelogAudience.ALL, includeUnreleased = true, type = "fix").first { it.version == "Unreleased" }.html
        assertThat(html).contains("Heartbeat fix.")
        assertThat(html).doesNotContain("List nesting.").doesNotContain("Bullet glyphs.").doesNotContain("Bump the Kotlin daemon heap.")
    }

    @Test
    fun `scope filter keeps only the matching scope`() {
        val html = renderer.entriesFrom(commitMarkdown, ChangelogAudience.ALL, includeUnreleased = true, scope = "editor").first { it.version == "Unreleased" }.html
        assertThat(html).contains("List nesting.")
        assertThat(html).doesNotContain("Heartbeat fix.").doesNotContain("Bullet glyphs.")
    }

    @Test
    fun `available types and scopes are discovered from commit-style entries`() {
        assertThat(renderer.availableTypesFrom(commitMarkdown, ChangelogAudience.ALL, includeUnreleased = true))
            // Canonical order: feat before fix before chore.
            .containsExactly("feat", "fix", "chore")
        assertThat(renderer.availableScopesFrom(commitMarkdown, ChangelogAudience.ALL, includeUnreleased = true))
            .containsExactly("build", "cluster", "editor", "generation")
    }

    @Test
    fun `an entry can carry multiple comma-separated scopes`() {
        // language=markdown
        val md =
            """
            # Changelog

            ## [Unreleased]

            - **[user]** fix(editor,pdf): **Glyph fix.** Renders in both.
            - feat(editor): **Editor-only thing.**
            """.trimIndent()

        // The multi-scope entry matches either of its scopes.
        assertThat(renderer.entriesFrom(md, ChangelogAudience.ALL, includeUnreleased = true, scope = "editor").first().html).contains("Glyph fix.")
        assertThat(renderer.entriesFrom(md, ChangelogAudience.ALL, includeUnreleased = true, scope = "pdf").first().html).contains("Glyph fix.")
        // pdf only belongs to the multi-scope entry, so the editor-only entry is excluded.
        assertThat(renderer.entriesFrom(md, ChangelogAudience.ALL, includeUnreleased = true, scope = "pdf").first().html).doesNotContain("Editor-only thing.")
        // Both scopes are discovered and both chips render.
        assertThat(renderer.availableScopesFrom(md, ChangelogAudience.ALL, includeUnreleased = true)).containsExactly("editor", "pdf")
        val html = renderer.entriesFrom(md, ChangelogAudience.ALL, includeUnreleased = true).first().html
        assertThat(html).contains(">editor<").contains(">pdf<")
    }

    @Test
    fun `legacy sections map their type from the heading and have no scope`() {
        // Released 1.0.0 uses legacy ### Added/### Fixed: Added->feat, Fixed->fix; no scopes.
        assertThat(renderer.availableTypesFrom(commitMarkdown, ChangelogAudience.ALL)).containsExactly("feat", "fix")
        assertThat(renderer.availableScopesFrom(commitMarkdown, ChangelogAudience.ALL)).isEmpty()

        val onlyFeat = renderer.entriesFrom(commitMarkdown, ChangelogAudience.ALL, type = "feat").first { it.version == "1.0.0" }.html
        assertThat(onlyFeat).contains("Legacy feature.").doesNotContain("Legacy fix.")
    }

    @Test
    fun `a release summary paragraph renders above the entries`() {
        // language=markdown
        val md =
            """
            # Changelog

            ## [1.0.0] - 2026-01-01

            This release ships the editor and squashes a rendering bug.

            - **[user]** feat(editor): **Thing.** Does a thing.
            - **[dev]** fix(pdf): **Glyph fix.**
            """.trimIndent()

        val entry = renderer.entriesFrom(md, ChangelogAudience.ALL).first { it.version == "1.0.0" }
        assertThat(entry.html).contains("""<div class="changelog-summary">""")
        assertThat(entry.html).contains("This release ships the editor and squashes a rendering bug.")
        // The summary precedes the first entry group.
        assertThat(entry.html.indexOf("changelog-summary")).isLessThan(entry.html.indexOf("<h3>Features</h3>"))
    }

    @Test
    fun `bundled CHANGELOG parses cleanly for every view`() {
        // Regression guard: the real, shipped CHANGELOG.md must always parse. This fails if the file is
        // missing/empty, if no released version is found, or if an audience marker leaks into the output.
        ChangelogAudience.entries.forEach { view ->
            val entries = renderer.entries(view)
            assertThat(entries)
                .withFailMessage("Bundled CHANGELOG.md produced no entries for the %s view", view)
                .isNotEmpty()
            entries.forEach { entry ->
                assertThat(entry.version).matches("\\d+\\.\\d+\\.\\d+")
                assertThat(entry.date).matches("\\d{4}-\\d{2}-\\d{2}")
                assertThat(entry.html).isNotBlank()
                assertThat(entry.summary).isNotBlank()
                // A leading audience marker must be parsed away, never rendered as bold text on the item.
                // (A marker mentioned inside a code span — e.g. `**[user]**` in prose — is legitimate.)
                assertThat(entry.html)
                    .withFailMessage("Unparsed audience marker leaked into v%s (%s view): %s", entry.version, view, entry.html)
                    .doesNotContainPattern("<strong>\\[(?:user|dev|developer)]</strong>")
            }
        }
    }

    @Test
    fun `bundled Unreleased uses commit-style entries with scopes`() {
        // Enforces the convention: new entries carry type(scope), so the dialog has scopes to filter on.
        assertThat(renderer.availableScopes(ChangelogAudience.USER, includeUnreleased = true))
            .withFailMessage("Bundled [Unreleased] entries must use commit-style type(scope): prefixes")
            .isNotEmpty()
        assertThat(renderer.availableTypes(ChangelogAudience.ALL, includeUnreleased = true)).isNotEmpty()
    }

    @Test
    fun `user view shows everyone and user entries but not dev`() {
        val html = renderer.entriesFrom(taggedMarkdown, ChangelogAudience.USER).first { it.version == "1.2.0" }.html
        assertThat(html).contains("User feature.")
        assertThat(html).contains("Everyone item.")
        assertThat(html).doesNotContain("Dev refactor.")
        assertThat(html).doesNotContain("Dev-only fix.")
    }

    @Test
    fun `developer view shows everyone and dev entries but not user`() {
        val html = renderer.entriesFrom(taggedMarkdown, ChangelogAudience.DEVELOPER).first { it.version == "1.2.0" }.html
        assertThat(html).contains("Dev refactor.")
        assertThat(html).contains("Dev-only fix.")
        assertThat(html).contains("Everyone item.")
        assertThat(html).doesNotContain("User feature.")
    }

    @Test
    fun `all view shows every entry`() {
        val html = renderer.entriesFrom(taggedMarkdown, ChangelogAudience.ALL).first { it.version == "1.2.0" }.html
        assertThat(html).contains("User feature.", "Dev refactor.", "Everyone item.", "Dev-only fix.")
    }

    @Test
    fun `versions with no entries for the view are dropped`() {
        // 1.1.0 has only a dev entry — absent from the user view, present in the developer view.
        val userVersions = renderer.entriesFrom(taggedMarkdown, ChangelogAudience.USER).map { it.version }
        val devVersions = renderer.entriesFrom(taggedMarkdown, ChangelogAudience.DEVELOPER).map { it.version }
        assertThat(userVersions).contains("1.2.0").doesNotContain("1.1.0")
        assertThat(devVersions).contains("1.2.0", "1.1.0")
    }

    @Test
    fun `Unreleased section is excluded by default`() {
        val all = renderer.entriesFrom(taggedMarkdown, ChangelogAudience.ALL)
        assertThat(all.map { it.version }).doesNotContain("Unreleased")
        assertThat(all.joinToString { it.html }).doesNotContain("Upcoming user feature")
    }

    @Test
    fun `Unreleased section is shown first as an unreleased entry when requested`() {
        val all = renderer.entriesFrom(taggedMarkdown, ChangelogAudience.ALL, includeUnreleased = true)
        val first = all.first()
        assertThat(first.version).isEqualTo("Unreleased")
        assertThat(first.released).isFalse()
        assertThat(first.date).isEmpty()
        assertThat(first.html).contains("Upcoming user feature.").contains("Upcoming plumbing.")
        // The released versions still follow, and all of them are flagged released.
        assertThat(all.drop(1)).allMatch { it.released }
        assertThat(all.map { it.version }).containsSequence("Unreleased", "1.2.0")
    }

    @Test
    fun `Unreleased respects the audience view`() {
        // The fixture's Unreleased has a [user] and a [dev] item — the user view keeps only the user one,
        // and a view with no matching Unreleased items drops the Unreleased entry entirely.
        val userView = renderer.entriesFrom(taggedMarkdown, ChangelogAudience.USER, includeUnreleased = true)
        val unreleased = userView.first { it.version == "Unreleased" }
        assertThat(unreleased.html).contains("Upcoming user feature.").doesNotContain("Upcoming plumbing.")
    }

    @Test
    fun `audience marker is stripped and replaced by a chip`() {
        val html = renderer.entriesFrom(taggedMarkdown, ChangelogAudience.ALL).first { it.version == "1.2.0" }.html
        // The raw "[user]" / "[dev]" marker text must not survive into the rendered HTML.
        assertThat(html).doesNotContain("[user]").doesNotContain("[dev]")
        assertThat(html).contains("changelog-badge--user").contains("changelog-badge--dev")
        // Untagged entries carry no audience chip.
        assertThat(html).doesNotContain("changelog-badge--everyone")
    }

    @Test
    fun `summary counts reflect the view`() {
        val userSummary = renderer.entriesFrom(taggedMarkdown, ChangelogAudience.USER).first { it.version == "1.2.0" }.summary
        val devSummary = renderer.entriesFrom(taggedMarkdown, ChangelogAudience.DEVELOPER).first { it.version == "1.2.0" }.summary
        // User view: 2 added (user feature + everyone), no fixes.
        assertThat(userSummary).isEqualTo("2 new features")
        // Developer view: 1 added (dev refactor + everyone = 2) and 1 fix.
        assertThat(devSummary).isEqualTo("2 new features, 1 fix")
    }

    private fun compareVersions(a: String, b: String): Int {
        val aParts = a.split(".").map { it.toInt() }
        val bParts = b.split(".").map { it.toInt() }
        for (i in 0..2) {
            val cmp = aParts[i].compareTo(bParts[i])
            if (cmp != 0) return cmp
        }
        return 0
    }
}
