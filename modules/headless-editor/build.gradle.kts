plugins {
    `java-library`
}

val verifyFrontendBuild by tasks.registering {
    description = "Verifies that the frontend build output exists"
    group = "verification"

    doLast {
        val distDir = file("dist")
        if (!distDir.exists() || !distDir.isDirectory) {
            throw GradleException(
                """
                Frontend build output not found at: ${distDir.absolutePath}

                Please run the frontend build first:
                  pnpm --filter @epistola/headless-editor build
                """.trimIndent(),
            )
        }
    }
}

val copyDistToResources by tasks.registering(Copy::class) {
    dependsOn(verifyFrontendBuild)
    from("dist")
    into(layout.buildDirectory.dir("resources/main/META-INF/resources/headless-editor"))

    outputs.upToDateWhen { layout.buildDirectory.dir("resources/main/META-INF/resources/headless-editor").get().asFile.exists() }
}

tasks.named("processResources") {
    dependsOn(copyDistToResources)
}
