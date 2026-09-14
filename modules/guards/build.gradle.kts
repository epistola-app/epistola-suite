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

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
