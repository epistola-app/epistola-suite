plugins {
    id("epistola-kotlin-conventions")
    id("epistola-kover-conventions")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    // Demo mode, as a module rather than a package inside the app — so it can be left out of the
    // image entirely.
    //
    // Demo mode is not a data-loading convenience: it gives every person who logs in a tenant of
    // their own, and can carry a shared secret that authenticates every /api endpoint against every
    // tenant with every permission. A profile flag is a weak boundary for that — anyone who can edit
    // a deployment's environment is one variable away. Shipping the code in a separate image makes
    // it a structural one: `epistola-suite:{version}` does not contain these classes, so no amount
    // of configuration can turn a production install into a demo.
    //
    // The host app takes this on `testAndDevelopmentOnly` (so `bootRun` and tests have it) and on
    // `implementation` only under `-PdemoImage=true`, which is what the `-demo` image is built with.
    // See apps/epistola/build.gradle.kts and docs/auth.md.

    // Core — mediator, security, IDs, tenants/environments/catalog commands, users.
    api(project(":modules:epistola-core"))

    // Shared web/UI toolkit — the redirect helper the post-login target uses.
    implementation(project(":modules:epistola-web"))

    // The REST API's key filter: the shared-secret filter hands it a validated principal.
    implementation(project(":modules:rest-api"))

    // Quality ledger — the demo seeds both halves of it on the showcase template.
    implementation(project(":modules:epistola-quality"))

    // Spring Boot — base + web (the servlet filter) + JDBC (the demo API key row).
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
}
