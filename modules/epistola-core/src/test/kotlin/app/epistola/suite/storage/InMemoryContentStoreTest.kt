// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.storage

import org.junit.jupiter.api.Tag

@Tag("unit")
class InMemoryContentStoreTest : ContentStoreContractTest() {

    override fun createStore(): ContentStore = InMemoryContentStore()
}
