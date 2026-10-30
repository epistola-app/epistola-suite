// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureDefaultsTest {
    @Test
    fun `unknown feature is disabled`() {
        assertFalse(FeatureDefaults().isEnabled(FeatureKey.of("unknown-feature")))
    }

    @Test
    fun `feedback is freely usable and defaults on here`() {
        assertTrue(FeatureDefaults().isEnabled(KnownFeatures.SUPPORT_FEEDBACK))
    }

    @Test
    fun `alpha editor features default off here`() {
        val defaults = FeatureDefaults()
        assertFalse(defaults.isEnabled(KnownFeatures.QUALITY))
        assertFalse(defaults.isEnabled(KnownFeatures.AI_CHAT))
        assertFalse(defaults.isEnabled(KnownFeatures.CATALOG_PUBLISHING))
    }

    @Test
    fun `organising catalogs is stable and on by default`() {
        assertTrue(FeatureDefaults().isEnabled(KnownFeatures.RESOURCE_RELOCATION))
    }
}
