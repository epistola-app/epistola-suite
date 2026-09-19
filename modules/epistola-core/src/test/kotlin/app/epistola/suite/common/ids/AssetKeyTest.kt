// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.common.ids

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * An asset's key is its public address, not its identity.
 *
 * It was a UUID until assets were the only resource type addressed by a machine identifier, which
 * made a catalog naming its assets readably impossible to install. These tests pin the two halves
 * of that change that are easy to undo by accident: a readable key is accepted, and every key an
 * installation already holds still is.
 */
class AssetKeyTest {

    @Test
    fun `accepts a readable key`() {
        assertThat(AssetKey.of("municipality-mark").value).isEqualTo("municipality-mark")
    }

    @Test
    fun `accepts the UUID string every existing asset is keyed by`() {
        // The reason the migration needs no data rewriting: an existing key is already valid text.
        // Note the leading digit — the stricter slug rule the other key domains use would reject it.
        val existing = "01966a00-0000-7000-8000-000000000001"
        assertThat(AssetKey.of(existing).value).isEqualTo(existing)
        assertThat(AssetKey.of(UUID.fromString(existing)).value).isEqualTo(existing)
    }

    @Test
    fun `a generated key is still a UUID string`() {
        // Nothing asks a person to name an asset yet, so an upload behaves exactly as it did. What
        // changed is which keys can be read, not which are written.
        val generated = AssetKey.generate()
        assertThat(UUID.fromString(generated.value)).isNotNull()
    }

    @Test
    fun `round-trips through its string form`() {
        // ContentKey builds storage paths from `value`, so this is what keeps an existing asset's
        // blob reachable at the path it was written to.
        val key = AssetKey.of("01966a00-0000-7000-8000-000000000001")
        assertThat(key.toString()).isEqualTo(key.value)
        assertThat(AssetKey.of(key.toString())).isEqualTo(key)
    }

    @Test
    fun `refuses keys the database domain would refuse`() {
        // Kept in step with ASSET_KEY's CHECK, so a key that binds cannot fail at the column.
        listOf(
            "Municipality-Mark" to "uppercase",
            "municipality_mark" to "underscore",
            "-leading" to "leading hyphen",
            "trailing-" to "trailing hyphen",
            "double--hyphen" to "consecutive hyphens",
            "" to "empty",
            "a".repeat(51) to "over 50 characters",
        ).forEach { (value, why) ->
            assertThatThrownBy { AssetKey.of(value) }
                .describedAs("should refuse %s: '%s'", why, value)
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `validateOrNull reports a bad key instead of throwing`() {
        assertThat(AssetKey.validateOrNull("municipality-mark")).isNotNull()
        assertThat(AssetKey.validateOrNull("Not A Key")).isNull()
    }
}
