plugins {
    id("org.jetbrains.kotlinx.kover")
}

// Coverage is measured only when a Kover task is asked for (`koverXmlReport`,
// `koverHtmlReport`, ...). The agent instruments every class a test JVM loads:
// measured on ThemeQueriesTest (2 November), it made the class 20-35 % slower and
// its Spring context boots about 50 % slower, a cost every pull request paid for a
// report only the `main` coverage badge reads.
val coverageRequested =
    gradle.startParameter.taskNames.any { requested ->
        requested.substringAfterLast(':').startsWith("kover")
    }

kover {
    currentProject {
        instrumentation {
            disabledForAll.set(!coverageRequested)
            // Custom test tasks are developer conveniences for running subsets.
            // The default test task already runs everything — Kover only needs that.
            // A task left out of this list is one every Kover report runs: `modelTest`
            // (random command sequences, minutes in epistola-core) and `perfTest` are
            // opt-in, so they stay out of coverage too.
            disabledForTestTasks.addAll("integrationTest", "unitTest", "uiTest", "modelTest", "perfTest")
        }
    }
}
