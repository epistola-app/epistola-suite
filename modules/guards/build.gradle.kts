plugins {
    id("epistola-kotlin-conventions")
    id("io.spring.dependency-management")
}

// The repository-wide guards read source files off disk. They need no Spring context, no database
// and no editor bundle, so this module deliberately depends on nothing: running them used to mean
// building the whole application first, which is why the fast feedback loop was `pnpm build` plus a
// Spring Boot compile away. Dependency management is here only to version the test libraries.
the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

// These guards read the repository off disk at run time, so Gradle cannot see the files they scan
// as task inputs. Left to up-to-date checking, the task is skipped whenever this module itself has
// not changed — which is almost always, now that the guards live apart from the code they police —
// and reports a stale pass over a tree it never looked at. They take seconds; always run them.
tasks.withType<Test>().configureEach {
    outputs.upToDateWhen { false }
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
