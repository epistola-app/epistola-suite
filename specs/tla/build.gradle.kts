// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import java.security.MessageDigest

// TLC checks the TLA+ models here (README.md): `./gradlew :specs:tla:tlc`. Not part of `check` yet.

plugins {
    base
}

val tlaTools: Configuration = configurations.create("tlaTools")

dependencies {
    tlaTools(libs.tla2tools)
}

// The jar comes from a GitHub release, not a repository that signs it: it must be the one checked.
val tlaToolsSha256 = "936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88"

// TLC writes a trace module next to the spec when it finds a violation: it runs on a copy.
val specCopy = tasks.register<Sync>("tlcSpecs") {
    from(layout.projectDirectory) { include("*.tla", "*.cfg") }
    into(layout.buildDirectory.dir("tlc/specs"))
}

/**
 * Checks [spec] under [config]. [violated] names an invariant the config is meant to break (the code
 * before a fix, proving the model finds the race): only it is checked, as TLC stops at the first
 * violation and which comes first varies. Null: every invariant of [config] must hold.
 */
fun tlc(name: String, spec: String, config: String, violated: String?) = tasks.register<JavaExec>(name) {
    group = "verification"
    description = "TLC on $spec with $config: ${violated?.let { "expects $it violated" } ?: "expects no error"}"
    dependsOn(specCopy)
    inputs.files(specCopy)
    inputs.files(tlaTools)
    val dir = layout.buildDirectory.dir("tlc/specs")
    val log = layout.buildDirectory.file("tlc/$name.log")
    outputs.file(log)
    classpath = tlaTools
    mainClass = "tlc2.TLC"
    jvmArgs("-XX:+UseParallelGC")
    workingDir(dir)
    val checked = if (violated == null) config else "$name.cfg"
    args("-workers", "auto", "-cleanup", "-metadir", layout.buildDirectory.dir("tlc/states/$name").get().asFile.path, "-config", checked, spec)
    isIgnoreExitValue = true
    val sha = tlaToolsSha256
    doFirst {
        val jar = (this as JavaExec).classpath.singleFile
        val actual = MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
        check(actual == sha) { "${jar.name} has SHA-256 $actual, not the $sha checked" }
        if (violated != null) {
            val base = dir.get().file(config).asFile.readText().substringBefore("INVARIANTS")
            dir.get().file(checked).asFile.writeText("${base}INVARIANTS\n    TypeOK\n    $violated\n")
        }
        standardOutput = log.get().asFile.also { it.parentFile.mkdirs() }.outputStream()
    }
    doLast {
        val output = log.get().asFile.readText()
        val exit = (this as JavaExec).executionResult.get().exitValue
        val outcome = Regex("Invariant (\\w+) is violated").find(output)?.groupValues?.get(1)
        when {
            violated == null && exit != 0 -> throw GradleException("TLC found an error in $spec with $config (exit $exit):\n$output")
            violated != null && outcome != violated -> throw GradleException("TLC was to find $violated violated in $spec with $config, found ${outcome ?: "none"} (exit $exit):\n$output")
        }
        output.lines().lastOrNull { it.contains("distinct states found") }?.let { logger.lifecycle(it.trim()) }
    }
}

// The code before the fix: each race the model is to find, one invariant each.
val races = listOf("NeverUseEachOther", "ReleasePinsReleases", "PinnedReleasePresent").map { invariant ->
    tlc("tlcCatalogConcurrencyFinds$invariant", "CatalogConcurrency.tla", "CatalogConcurrency.cfg", violated = invariant)
}
val fixed = tlc("tlcCatalogConcurrencyFixed", "CatalogConcurrency.tla", "CatalogConcurrencyFixed.cfg", violated = null)

tasks.register("tlc") {
    group = "verification"
    description = "Runs TLC on every TLA+ model and configuration"
    dependsOn(races, fixed)
}
