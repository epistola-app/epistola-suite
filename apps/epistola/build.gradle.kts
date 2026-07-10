import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.TextReportRenderer
import org.cyclonedx.model.Component
import java.io.File

plugins {
    id("epistola-kotlin-conventions")
    id("epistola-kover-conventions")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.graalvm.buildtools.native") apply false
    id("org.cyclonedx.bom")
    id("com.github.jk1.dependency-license-report")
}

// Conditionally apply native plugin only when building native images
val buildNativeImage = providers.gradleProperty("nativeImage").orNull?.toBoolean() ?: false
if (buildNativeImage) {
    apply(plugin = "org.graalvm.buildtools.native")
}

// Security override: Spring Boot 4.0.6 manages tomcat-embed-core 11.0.21,
// affected by CVE-2026-41293 and CVE-2026-43512 (both CRITICAL). Pin the
// embedded Tomcat to the fixed 11.0.22 until the Spring Boot BOM catches up.
// Read by io.spring.dependency-management to override the managed version.
extra["tomcat.version"] = "11.0.22"

dependencies {
    // Core business logic module (includes template-model, generation transitively)
    implementation(project(":modules:epistola-core"))

    // Shared web/UI toolkit (HTMX functional-web DSL) used by the app handlers and
    // by per-feature UI modules.
    implementation(project(":modules:epistola-web"))

    // OSS release/version-check feature (daily check + tenant-home banner via the HomeNotice SPI)
    implementation(project(":modules:epistola-version-check"))

    // Audit feature (PII-free "who did what, when" trail: recorder, viewer, schema)
    implementation(project(":modules:epistola-audit"))

    // Support module (optional commercial-tier hub integration; off by default)
    implementation(project(":modules:epistola-support"))

    // Feedback feature — full domain + UI + hub sync (freely usable; the hub sync server
    // component is gated on epistola.support.enabled). Pulls in the feedback domain.
    implementation(project(":modules:epistola-support-feedback"))

    // Shared snapshot sync — the SnapshotSyncPort + TenantSnapshotSyncService both support features
    // ride. Pulled in transitively by backups/upgrading; listed explicitly so its beans (no-op
    // fallback, hub adapter, sync service) are unambiguously component-scanned.
    implementation(project(":modules:epistola-support-snapshots"))

    // Catalog backups — the daily retained-snapshot scheduler, restore, and the Backups UI. Gated
    // by the `support-backups` feature toggle and (for hub calls) epistola.support.enabled.
    implementation(project(":modules:epistola-support-backups"))

    // Upgrading (compatibility checks) — reads the company-side compatibility results for the
    // tenant's catalogs, shows the Upgrading UI, and owns a snapshot-freshness sweep. Separate
    // feature from Backups (gated by `support-upgrading`); both ride the shared snapshot sync.
    implementation(project(":modules:epistola-support-upgrading"))

    // Telemetry — the dedicated OTLP leg that forwards application logs + metrics to epistola-hub
    // when enabled (epistola.support.telemetry.enabled) and the installation is entitled. ADR 0006.
    implementation(project(":modules:epistola-support-telemetry"))

    // Catalog module (catalog exchange for sharing templates)

    // Load test module (load testing functionality)
    implementation(project(":modules:loadtest"))

    // REST API module (controllers for external systems)
    implementation(project(":modules:rest-api"))

    // MCP module (Model Context Protocol server for AI assistants)
    implementation(project(":modules:epistola-mcp"))

    // UI/Frontend modules
    implementation(project(":modules:editor"))

    // Spring Boot - UI layer concerns
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-authorization-server")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-session-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.commonmark:commonmark:0.29.0")

    // HTMX for dynamic UI
    implementation(libs.htmx.spring.boot.thymeleaf)

    // Flyway for migrations (app deployment concern)
    implementation("org.flywaydb:flyway-database-postgresql")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Thymeleaf extras
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")

    // Jackson for Thymeleaf
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Development
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    // Runtime dependencies
    runtimeOnly("io.micrometer:micrometer-registry-otlp")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")

    // Annotation processors
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Testing
    testImplementation(project(":modules:testing"))
    // Hub client error types (e.g. HubUnavailableException) for the support-page connectivity tests.
    testImplementation(libs.epistola.hub.client)
    testImplementation("org.springframework.boot:spring-boot-micrometer-tracing-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-authorization-server-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-thymeleaf-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-grafana")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation(libs.playwright)
    // JsonPath for readable JSON assertions in smoke / wire tests where Jackson
    // tree navigation (body["a"]["b"]["c"].asInt()) gets noisy. Used by
    // CollectEndpointSmokeIT; preferred over JSONata (which lives in :modules:generation
    // for runtime template expressions, not assertions).
    testImplementation("com.jayway.jsonpath:json-path:3.0.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Enable BuildProperties bean by generating build-info.properties
springBoot {
    buildInfo()
}

// Configure CycloneDX SBOM generation for backend dependencies
tasks.cyclonedxDirectBom {
    // CycloneDx resolves the runtime classpath but doesn't declare implicit dependencies
    // on the project jar tasks that produce those artifacts. Declaring the runtimeClasspath
    // as an input lets Gradle infer the correct task dependencies automatically.
    inputs.files(configurations.runtimeClasspath)
    projectType = Component.Type.APPLICATION
    includeBomSerialNumber = true
    includeLicenseText = false
    jsonOutput = layout.buildDirectory.file("sbom/bom.json").get().asFile
}

// Third-party license inventory for the backend (JVM/Maven) runtime classpath.
// apps:epistola pulls every :modules:* in as implementation(project(...)), so its
// runtimeClasspath carries all transitive external artifacts. Our own first-party
// artifacts (app.epistola*) are AGPL-covered by our LICENSE and excluded here.
licenseReport {
    configurations = arrayOf("runtimeClasspath")
    excludeOwnGroup = true
    excludeGroups = arrayOf("app\\.epistola.*")
    filters = arrayOf(LicenseBundleNormalizer())
    renderers = arrayOf(TextReportRenderer("third-party-backend.txt"))
}

// Merge the backend license report, the frontend (npm) report, and the bundled
// font (OFL 1.1) notices into a single THIRD-PARTY-NOTICES.md that ships in the
// image and as a release artifact.
val generateThirdPartyNotices by tasks.registering {
    group = "verification"
    description = "Generate a consolidated THIRD-PARTY-NOTICES.md (backend + frontend + fonts)"
    dependsOn("generateLicenseReport")

    val backendReport = layout.buildDirectory.file("reports/dependency-license/third-party-backend.txt")
    val frontendReport = rootProject.file("modules/editor/build/THIRD-PARTY-NOTICES-frontend.txt")
    val fontNotices = buildList {
        add(rootProject.file("modules/generation/src/main/resources/fonts/LICENSE-LiberationFonts"))
        rootProject.file("modules/epistola-core/src/main/resources/epistola/fonts")
            .listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?.forEach { dir ->
                val ofl = File(dir, "OFL.txt")
                if (ofl.exists()) add(ofl)
            }
    }
    val outputFile = layout.buildDirectory.file("notices/THIRD-PARTY-NOTICES.md")

    inputs.file(backendReport)
    inputs.files(fontNotices)
    outputs.file(outputFile)

    doLast {
        if (!frontendReport.exists()) {
            throw GradleException(
                """
                Frontend license report not found at: ${frontendReport.absolutePath}

                Generate it first:
                  pnpm --filter @epistola/editor notices
                """.trimIndent(),
            )
        }

        val md = buildString {
            appendLine("# Third-Party Notices")
            appendLine()
            appendLine("Epistola Suite is distributed under the GNU Affero General Public License v3.0")
            appendLine("(see the `LICENSE` file at the repository root). The distributed Docker image bundles")
            appendLine("the third-party components listed below; their copyright and license notices are")
            appendLine("reproduced here to satisfy their license terms.")
            appendLine()
            appendLine("This file is generated — do not edit by hand. Regenerate with:")
            appendLine()
            appendLine("```")
            appendLine("pnpm --filter @epistola/editor notices")
            appendLine("./gradlew :apps:epistola:generateThirdPartyNotices")
            appendLine("```")
            appendLine()
            appendLine("## Backend (JVM / Maven) dependencies")
            appendLine()
            appendLine("```")
            append(backendReport.get().asFile.readText())
            appendLine("```")
            appendLine()
            appendLine("## Frontend (npm) dependencies")
            appendLine()
            appendLine("```")
            append(frontendReport.readText())
            appendLine("```")
            appendLine()
            appendLine("## Bundled fonts (SIL Open Font License 1.1)")
            appendLine()
            fontNotices.forEach { f ->
                appendLine("### ${f.parentFile.name}/${f.name}")
                appendLine()
                appendLine("```")
                append(f.readText())
                appendLine("```")
                appendLine()
            }
        }

        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(md)
        logger.lifecycle("Wrote third-party notices to ${out.absolutePath}")
    }
}

// Self-hosted vendor scripts: copied from the pnpm-installed packages instead of a CDN
// (the CSP allows no external script hosts).
val vendoredScripts = mapOf(
    "HTMX" to "node_modules/htmx.org/dist/htmx.min.js",
    "Scalar API Reference" to "node_modules/@scalar/api-reference/dist/browser/standalone.js",
    "Inter font" to "node_modules/@fontsource/inter/400.css",
)

val verifyHtmxVendored by tasks.registering {
    description = "Verifies the vendored frontend bundles exist (from pnpm install)"
    group = "verification"
    val bundles = vendoredScripts.mapValues { (_, path) -> rootProject.file(path) }

    doLast {
        bundles.forEach { (name, bundle) ->
            if (!bundle.exists()) {
                throw GradleException(
                    """
                    Vendored $name bundle not found at: ${bundle.absolutePath}

                    Please install frontend dependencies first:
                      pnpm install && pnpm build
                    """.trimIndent(),
                )
            }
        }
    }
}

tasks.processResources {
    // Copy SBOM to JAR resources for Docker embedding
    dependsOn(tasks.cyclonedxDirectBom)
    dependsOn(verifyHtmxVendored)
    from(layout.buildDirectory.file("sbom/bom.json")) {
        into("META-INF/sbom")
    }
    // Best-effort embed of the consolidated third-party notices for Docker distribution.
    // Deliberately NOT wired to generateThirdPartyNotices: the jk1 license-report task is
    // not configuration-cache compatible, so a hard dependency would discard the config
    // cache on every build/test/bootRun. The notices are a release/distribution artifact —
    // CI runs `generateThirdPartyNotices` explicitly before bootBuildImage; locally, run it
    // first if you want the file inside your image. When absent, nothing is embedded.
    from(layout.buildDirectory.file("notices/THIRD-PARTY-NOTICES.md")) {
        into("META-INF/licenses")
    }
    // Copy design-system assets so Spring Boot serves them at /design-system/*
    from(rootProject.file("modules/design-system")) {
        include("*.css", "icons.svg", "epistola-logo.svg")
        into("static/design-system")
    }
    // Self-host HTMX so Spring Boot serves it at /js/vendor/htmx.min.js
    from(rootProject.file("node_modules/htmx.org/dist/htmx.min.js")) {
        into("static/js/vendor")
    }
    // Self-host the Scalar API-reference viewer at /js/vendor/scalar-api-reference.js
    from(rootProject.file("node_modules/@scalar/api-reference/dist/browser/standalone.js")) {
        rename { "scalar-api-reference.js" }
        into("static/js/vendor")
    }
    // Self-host the Inter font (OFL 1.1, vendored from @fontsource/inter) at
    // /fonts/inter/* so no requests go to Google Fonts (ADR 0010: no external origins)
    from(rootProject.file("node_modules/@fontsource/inter")) {
        include("400.css", "500.css", "600.css", "700.css")
        into("static/fonts/inter")
    }
    from(rootProject.file("node_modules/@fontsource/inter/files")) {
        include("inter-*-400-normal.*", "inter-*-500-normal.*", "inter-*-600-normal.*", "inter-*-700-normal.*")
        into("static/fonts/inter/files")
    }
    // Copy changelog markdown into app resources
    from(rootProject.file("CHANGELOG.md")) {
        into("changelog")
    }
}

// Convenience task for generating SBOM standalone
tasks.register("generateSbom") {
    group = "verification"
    description = "Generate CycloneDX SBOM for backend dependencies"
    dependsOn(tasks.cyclonedxDirectBom)
}

// Local-dev database reset. The application never resets a database (FlywayConfig
// has no clean() call); wiping local data is a deliberate developer action.
// The compose Postgres stores data on tmpfs, so force-recreating the container
// yields an empty database — the app re-migrates from scratch on next start.
tasks.register<Exec>("resetLocalDb") {
    group = "local dev"
    description = "Recreate the throwaway local Postgres container (WIPES all local data); the app re-migrates on next start"
    workingDir = layout.projectDirectory.asFile
    commandLine("docker", "compose", "-f", "docker/docker-compose.yaml", "up", "-d", "--force-recreate", "--wait", "postgres")
}

// Docker image configuration
// Default: JVM image (recommended, stable)
// Use -PnativeImage=true to build a native image (currently broken due to JDBI/Kotlin reflection)

// Build a custom run image based on run-noble-base with only fontconfig and DejaVu fonts added.
// This cuts ~565MB compared to the full run image while still supporting Java AWT font rendering.
val buildRunImage by tasks.registering(Exec::class) {
    group = "docker"
    description = "Build custom CNB run image with fontconfig and fonts"
    commandLine("docker", "build", "-t", "epistola-run:noble", file("docker/run-image").absolutePath)
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
