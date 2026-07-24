plugins {
    id("org.jetbrains.kotlinx.kover")
}

kover {
    currentProject {
        instrumentation {
            // Custom test tasks are developer conveniences for running subsets.
            // The default test task already runs everything — Kover only needs that.
            disabledForTestTasks.addAll("integrationTest", "unitTest", "uiTest")
        }
    }
}
