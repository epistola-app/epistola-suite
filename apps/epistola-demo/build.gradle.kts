plugins {
    id("epistola-kotlin-conventions")
    id("epistola-kover-conventions")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// Same override and rationale as apps/epistola — this app produces its own boot jar, so it
// resolves its own Tomcat. Keep the two in step.
extra["tomcat.version"] = "11.0.25"

dependencies {
    // The whole suite, as a library. This app adds demo mode on top and changes nothing else.
    //
    // Demo mode is why the two apps exist. It gives every person who logs in a tenant of their own,
    // and can carry a shared secret that authenticates every /api endpoint against every tenant with
    // every permission. A profile flag would be a weak boundary for that — anyone who can edit a
    // deployment's environment is one variable away — so the code lives in a different artifact
    // instead: `epistola-suite:{version}` is built from apps/epistola and cannot become a demo,
    // because the classes are not in it. See docs/auth.md "Two images".
    implementation(project(":apps:epistola"))

    // Core — mediator, security, IDs, tenants/environments/catalog commands, users.
    implementation(project(":modules:epistola-core"))

    // The REST API's key filter: the shared-secret filter hands it a validated principal through
    // ApiPreAuthenticationFilter.
    implementation(project(":modules:rest-api"))

    // Quality ledger — the demo seeds both halves of it on the showcase template.
    implementation(project(":modules:epistola-quality"))

    // Spring Boot — base + web (the servlet filter) + JDBC (the demo API key row) + security.
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // Metrics — the shared secret counts on the shared API auth meter.
    implementation("io.micrometer:micrometer-core")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Jackson (DemoLoader takes an ObjectMapper)
    implementation("tools.jackson.core:jackson-databind")

    // Testing — IntegrationTestBase and the shared test principal live in modules:testing; the
    // host app's own main classes (ApiProblemAuthenticationEntryPoint, SecurityConfig) come with
    // the project dependency above.
    testImplementation(project(":modules:testing"))
    // DemoShowcaseQualityIntegrationTest drives the real quality sources over the shipped demo
    // catalog — it moved here from epistola-quality with the catalog it asserts on.
    testImplementation(project(":modules:epistola-quality"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    // Mirrors apps/epistola: empty directories carry nothing, and a stale one left behind by a
    // cached compileKotlin output is actively misleading about what the artifact contains.
    includeEmptyDirs = false
}

// The same custom run image apps/epistola uses — run-noble-base plus fontconfig and DejaVu fonts,
// which Java AWT font rendering needs. Reuses that project's Dockerfile and tag rather than
// duplicating either, so the two images cannot drift.
val buildRunImage = tasks.register<Exec>("buildRunImage") {
    group = "docker"
    description = "Build custom CNB run image with fontconfig and fonts"
    commandLine("docker", "build", "-t", "epistola-run:noble", project(":apps:epistola").file("docker/run-image").absolutePath)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootBuildImage>("bootBuildImage") {
    dependsOn(buildRunImage)
    runImage.set("epistola-run:noble")
    pullPolicy.set(org.springframework.boot.buildpack.platform.build.PullPolicy.IF_NOT_PRESENT)
    environment.set(
        mapOf(
            "BP_JVM_VERSION" to "25",
        ),
    )
}
