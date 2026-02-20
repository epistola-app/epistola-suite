package app.epistola.suite.storage

import org.junit.jupiter.api.Tag

@Tag("unit")
class InMemoryContentStoreTest : ContentStoreContractTest() {

    override fun createStore(): ContentStore = InMemoryContentStore()
}
